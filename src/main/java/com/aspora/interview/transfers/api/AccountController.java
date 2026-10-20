package com.aspora.interview.transfers.api;

import com.aspora.interview.transfers.domain.Account;
import com.aspora.interview.transfers.domain.ApiException;
import com.aspora.interview.transfers.domain.ErrorCode;
import com.aspora.interview.transfers.domain.LedgerEntry;
import com.aspora.interview.transfers.domain.Transfer;
import com.aspora.interview.transfers.store.AccountStore;
import com.aspora.interview.transfers.store.LedgerStore;
import com.aspora.interview.transfers.store.TransferStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public record NewAccount(String userId, String currency, Long openingBalanceMinor) {
    }

    private final AccountStore accounts;
    private final LedgerStore ledger;
    private final TransferStore transfers;

    public AccountController(AccountStore accounts, LedgerStore ledger, TransferStore transfers) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.transfers = transfers;
    }

    @PostMapping("/accounts")
    public ResponseEntity<Account> create(@RequestBody NewAccount body) {
        if (body.userId() == null || body.userId().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION, "userId is required");
        }
        if (body.currency() == null || !CURRENCY.matcher(body.currency()).matches()) {
            throw new ApiException(ErrorCode.VALIDATION, "currency must be a 3-letter upper-case code");
        }
        long opening = body.openingBalanceMinor() == null ? 0 : body.openingBalanceMinor();
        if (opening < 0) {
            throw new ApiException(ErrorCode.VALIDATION, "openingBalanceMinor must not be negative");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(accounts.insert(body.userId(), body.currency(), opening));
    }

    @GetMapping("/accounts/{id}")
    public Account get(@PathVariable UUID id) {
        return require(id);
    }

    @GetMapping("/accounts/{id}/ledger")
    public List<LedgerEntry> ledger(@PathVariable UUID id,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before) {
        require(id);
        return ledger.listByAccount(id, before, clamp(limit));
    }

    @GetMapping("/accounts/{id}/transfers")
    public List<Transfer> transfers(@PathVariable UUID id,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant before) {
        require(id);
        return transfers.listByFromAccount(id, before, clamp(limit));
    }

    private Account require(UUID id) {
        return accounts.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "no account " + id));
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, 200));
    }
}
