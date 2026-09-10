package com.mendel.transactions.infrastructure.persistence;

import com.mendel.transactions.domain.SaveResult;
import com.mendel.transactions.domain.Transaction;
import com.mendel.transactions.domain.TransactionRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** In-memory {@link TransactionRepository}, with secondary indexes by type and by parent. */
@Repository
public class InMemoryTransactionRepository implements TransactionRepository {

    @Override
    public SaveResult save(Transaction transaction) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public Optional<Transaction> findById(long id) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public boolean existsById(long id) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public List<Long> findIdsByType(String type) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public List<Long> findChildIds(long parentId) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    @Override
    public void deleteAll() {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
