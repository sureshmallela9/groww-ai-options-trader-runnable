package com.optionstrader.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TradingSettingsRepository extends JpaRepository<TradingSettingsEntity, String> { }