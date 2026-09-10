package com.mendel.transactions.application;

import com.mendel.transactions.domain.SaveResult;
import com.mendel.transactions.domain.Transaction;
import com.mendel.transactions.domain.TransactionRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/** Default implementation of both sides of the service, backed by a {@link TransactionRepository}. */
@Service
public class DefaultTransactionService implements TransactionCommandService, TransactionQueryService {

    private final TransactionRepository repository;

    public DefaultTransactionService(TransactionRepository repository) {
        this.repository = repository;
    }

    @Override
    public SaveResult save(Transaction transaction) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public List<Long> findIdsByType(String type) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public double sumLinkedTo(long transactionId) {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
