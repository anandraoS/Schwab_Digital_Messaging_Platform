package com.example.messaging.repository;

import com.example.messaging.domain.RecipientPreference;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipientPreferenceRepository extends JpaRepository<RecipientPreference, String> {}
