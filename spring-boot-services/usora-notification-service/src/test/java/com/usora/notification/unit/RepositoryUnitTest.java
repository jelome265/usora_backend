package com.usora.notification.unit;

import com.usora.notification.model.Notification;
import com.usora.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.data.jpa.test.autoconfigure.TestEntityManager;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.test.context.TestPropertySource;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
class RepositoryUnitTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private NotificationRepository notificationRepository;

    @Test
    void saveNotification_ShouldPersistEntity() {
        Notification notification = new Notification();
        notification.setRecipientId("user-001");
        notification.setType("EMAIL");
        notification.setTitle("Test Notification");
        notification.setMessage("This is a test message.");
        notification.setCreatedAt(LocalDateTime.now());
        notification.setRead(false);

        Notification saved = entityManager.persistAndFlush(notification);

        assertNotNull(saved.getId());
        assertEquals("user-001", saved.getRecipientId());
    }

    @Test
    void findByRecipientId_ShouldReturnNotifications() {
        Notification n1 = new Notification();
        n1.setRecipientId("user-001");
        n1.setType("EMAIL");
        n1.setTitle("Title 1");
        n1.setMessage("Message 1");
        n1.setCreatedAt(LocalDateTime.now());
        n1.setRead(false);
        entityManager.persistAndFlush(n1);

        Notification n2 = new Notification();
        n2.setRecipientId("user-001");
        n2.setType("SMS");
        n2.setTitle("Title 2");
        n2.setMessage("Message 2");
        n2.setCreatedAt(LocalDateTime.now());
        n2.setRead(false);
        entityManager.persistAndFlush(n2);

        List<Notification> results = notificationRepository.findByRecipientId("user-001");

        assertEquals(2, results.size());
    }

    @Test
    void findByRecipientIdAndReadFalse_ShouldReturnUnreadOnly() {
        Notification read = new Notification();
        read.setRecipientId("user-002");
        read.setType("EMAIL");
        read.setTitle("Read");
        read.setMessage("Read message");
        read.setCreatedAt(LocalDateTime.now());
        read.setRead(true);
        entityManager.persistAndFlush(read);

        Notification unread = new Notification();
        unread.setRecipientId("user-002");
        unread.setType("SMS");
        unread.setTitle("Unread");
        unread.setMessage("Unread message");
        unread.setCreatedAt(LocalDateTime.now());
        unread.setRead(false);
        entityManager.persistAndFlush(unread);

        List<Notification> unreadResults = notificationRepository.findByRecipientIdAndReadFalse("user-002");

        assertEquals(1, unreadResults.size());
        assertFalse(unreadResults.get(0).isRead());
    }

    @Test
    void deleteByRecipientId_ShouldRemoveNotifications() {
        Notification n = new Notification();
        n.setRecipientId("user-003");
        n.setType("EMAIL");
        n.setTitle("To Delete");
        n.setMessage("Will be deleted");
        n.setCreatedAt(LocalDateTime.now());
        n.setRead(false);
        entityManager.persistAndFlush(n);

        notificationRepository.deleteByRecipientId("user-003");

        List<Notification> results = notificationRepository.findByRecipientId("user-003");
        assertTrue(results.isEmpty());
    }
}
