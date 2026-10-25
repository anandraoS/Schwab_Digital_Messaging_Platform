package com.example.messaging.repository;

import com.example.messaging.domain.MessageAttempt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageAttemptRepository extends JpaRepository<MessageAttempt, String> {

    List<MessageAttempt> findByMessageIdOrderByAttemptNoAsc(String messageId);

    Optional<MessageAttempt> findByMessageIdAndAttemptNo(String messageId, int attemptNo);

    int countByMessageId(String messageId);

    Optional<MessageAttempt> findFirstByMessageIdOrderByAttemptNoDesc(String messageId);

    Optional<MessageAttempt> findFirstByProviderIdAndProviderMessageIdOrderByStartedAtDesc(String providerId,
            String providerMessageId);
}
