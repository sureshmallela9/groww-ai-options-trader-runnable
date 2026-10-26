package com.optionstrader.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaperTradeRepository extends JpaRepository<PaperTradeEntity, String> {
    List<PaperTradeEntity> findAllByOrderByEntryTimeDesc();
}