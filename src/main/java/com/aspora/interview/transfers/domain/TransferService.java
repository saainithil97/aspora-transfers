package com.aspora.interview.transfers.domain;

import com.aspora.interview.transfers.fees.FeeRuleRegistry;
import com.aspora.interview.transfers.limits.DailyLimitChecker;
import com.aspora.interview.transfers.store.AccountStore;
import com.aspora.interview.transfers.store.LedgerStore;
import com.aspora.interview.transfers.store.TransferStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates transfers and moves money. Every balance change happens inside an explicit transaction here. */
@Service
public class TransferService {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public record CreateResult(Transfer transfer, boolean created) {
    }

    private final AccountStore accounts;
    private final TransferStore transfers;
    private final LedgerStore ledger;
    private final FeeRuleRegistry fees;
    private final DailyLimitChecker dailyLimit;
    private final TransactionTemplate tx;

    public TransferService(AccountStore accounts, TransferStore transfers, LedgerStore ledger,
            FeeRuleRegistry fees, DailyLimitChecker dailyLimit, TransactionTemplate tx) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.ledger = ledger;
        this.fees = fees;
        this.dailyLimit = dailyLimit;
        this.tx = tx;
    }

    public CreateResult create(String idempotencyKey, NewTransfer request) {
        String requestHash = RequestHash.of(request);
        Optional<Transfer> existing = transfers.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(requestHash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_MISMATCH,
                        "Idempotency-Key " + idempotencyKey + " was already used with a different request body");
            }
            return new CreateResult(existing.get(), false);
        }

        validate(request);
        Account source = accounts.find(request.fromAccountId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "no account " + request.fromAccountId()));
        if (!source.currency().equals(request.currency())) {
            throw new ApiException(ErrorCode.VALIDATION, "account currency is " + source.currency());
        }
        long fee = fees.feeFor(request.type(), request.currency(), request.amountMinor());
        dailyLimit.check(source.userId(), request.amountMinor());

        Transfer draft = new Transfer(UUID.randomUUID(), idempotencyKey, requestHash, request.type(), null,
                request.fromAccountId(), request.toAccountId(), request.beneficiaryRef(), request.amountMinor(), fee,
                request.currency(), null, null, null, null);
        Transfer created = tx.execute(status -> switch (request.type()) {
            case INTERNAL -> createInternal(draft);
            case PAYOUT -> createPayout(draft);
        });
        return new CreateResult(created, true);
    }

    private Transfer createInternal(Transfer draft) {
        Account from = null;
        Account to = null;
        for (UUID id : List.of(draft.fromAccountId(), draft.toAccountId()).stream().sorted().toList()) {
            Account locked = accounts.lockForUpdate(id)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "no account " + id));
            if (id.equals(draft.fromAccountId())) {
                from = locked;
            } else {
                to = locked;
            }
        }
        if (!to.currency().equals(draft.currency())) {
            throw new ApiException(ErrorCode.VALIDATION, "destination account currency is " + to.currency());
        }
        requireFunds(from, draft.debitMinor());

        accounts.adjustBalance(from.id(), -draft.debitMinor());
        accounts.adjustBalance(to.id(), draft.amountMinor());
        transfers.insert(withStatus(draft, TransferStatus.COMPLETED));
        ledger.insert(draft.id(), from.id(), LedgerEntry.Direction.DEBIT, draft.debitMinor());
        ledger.insert(draft.id(), to.id(), LedgerEntry.Direction.CREDIT, draft.amountMinor());
        return transfers.find(draft.id()).orElseThrow();
    }

    private Transfer createPayout(Transfer draft) {
        Account from = accounts.lockForUpdate(draft.fromAccountId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "no account " + draft.fromAccountId()));
        requireFunds(from, draft.debitMinor());

        accounts.adjustBalance(from.id(), -draft.debitMinor());
        transfers.insert(withStatus(draft, TransferStatus.PENDING));
        ledger.insert(draft.id(), from.id(), LedgerEntry.Direction.DEBIT, draft.debitMinor());
        ledger.insert(draft.id(), null, LedgerEntry.Direction.CREDIT, draft.debitMinor());
        return transfers.find(draft.id()).orElseThrow();
    }

    /** Hands a PENDING transfer to the dispatcher. */
    public Transfer markDispatched(UUID id) {
        return tx.execute(status -> {
            Transfer t = transfers.find(id)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "no transfer " + id));
            TransferState.assertTransition(t.status(), TransferStatus.DISPATCHED);
            transfers.updateStatus(id, TransferStatus.DISPATCHED);
            return withStatus(t, TransferStatus.DISPATCHED);
        });
    }

    /** Fails a transfer that never reached the rail and gives the money back. */
    public void fail(Transfer t, String reason) {
        tx.executeWithoutResult(status -> {
            TransferState.assertTransition(t.status(), TransferStatus.FAILED);
            transfers.markFailed(t.id(), reason);
            refund(t);
        });
    }

    /**
     * Returns amount + fee to the source account and writes the reversing ledger pair.
     * Must be called inside a transaction.
     */
    public void refund(Transfer t) {
        accounts.adjustBalance(t.fromAccountId(), t.debitMinor());
        ledger.insert(t.id(), t.fromAccountId(), LedgerEntry.Direction.CREDIT, t.debitMinor());
        ledger.insert(t.id(), null, LedgerEntry.Direction.DEBIT, t.debitMinor());
    }

    private static void requireFunds(Account account, long debitMinor) {
        if (account.balanceMinor() < debitMinor) {
            throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS,
                    "balance " + account.balanceMinor() + " is less than " + debitMinor);
        }
    }

    private static Transfer withStatus(Transfer t, TransferStatus status) {
        return new Transfer(t.id(), t.idempotencyKey(), t.requestHash(), t.type(), status, t.fromAccountId(),
                t.toAccountId(), t.beneficiaryRef(), t.amountMinor(), t.feeMinor(), t.currency(), t.railRef(),
                t.failureReason(), t.createdAt(), t.updatedAt());
    }

    private static void validate(NewTransfer r) {
        if (r.type() == null) {
            throw new ApiException(ErrorCode.VALIDATION, "type is required (INTERNAL or PAYOUT)");
        }
        if (r.fromAccountId() == null) {
            throw new ApiException(ErrorCode.VALIDATION, "fromAccountId is required");
        }
        if (r.amountMinor() == null || r.amountMinor() <= 0) {
            throw new ApiException(ErrorCode.VALIDATION, "amountMinor must be a positive integer");
        }
        if (r.currency() == null || !CURRENCY.matcher(r.currency()).matches()) {
            throw new ApiException(ErrorCode.VALIDATION, "currency must be a 3-letter upper-case code");
        }
        switch (r.type()) {
            case INTERNAL -> {
                if (r.toAccountId() == null) {
                    throw new ApiException(ErrorCode.VALIDATION, "toAccountId is required for INTERNAL");
                }
                if (r.toAccountId().equals(r.fromAccountId())) {
                    throw new ApiException(ErrorCode.VALIDATION, "fromAccountId and toAccountId must differ");
                }
                if (r.beneficiaryRef() != null) {
                    throw new ApiException(ErrorCode.VALIDATION, "beneficiaryRef is not allowed for INTERNAL");
                }
            }
            case PAYOUT -> {
                if (r.beneficiaryRef() == null || r.beneficiaryRef().isBlank()) {
                    throw new ApiException(ErrorCode.VALIDATION, "beneficiaryRef is required for PAYOUT");
                }
                if (r.toAccountId() != null) {
                    throw new ApiException(ErrorCode.VALIDATION, "toAccountId is not allowed for PAYOUT");
                }
            }
        }
    }
}
