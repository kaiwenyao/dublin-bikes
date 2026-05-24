package dev.kaiwen.bikes.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.kaiwen.bikes.config.MailProperties;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

class MailServiceTest {

    private JavaMailSender mailSender;
    private MailService mailService;

    @BeforeEach
    void setUp() throws Exception {
        mailSender = mock(JavaMailSender.class);
        TemplateEngine templateEngine = mock(TemplateEngine.class);
        when(templateEngine.process(any(String.class), any(Context.class))).thenReturn("<p>123456</p>");
        MailProperties mailProperties =
                new MailProperties("bikes@example.com", "Dublin Bikes", "https://example.com");
        mailService = new MailService(provider(mailSender), templateEngine, mailProperties);
        setField("mailHost", "smtp.example.com");
    }

    @Test
    void sendVerificationEmail_usesConfiguredMailSender() {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        mailService.sendVerificationEmail("alice@example.com", "123456", 5, "activation-token");

        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendVerificationEmail_skipsWhenSenderUnavailable() throws Exception {
        mailService =
                new MailService(provider(null), mock(TemplateEngine.class), new MailProperties("bikes@example.com", null, null));
        setField("mailHost", "smtp.example.com");

        mailService.sendVerificationEmail("alice@example.com", "123456", 5, "activation-token");

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    private void setField(String name, Object value) throws Exception {
        var field = MailService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mailService, value);
    }

    private static ObjectProvider<JavaMailSender> provider(JavaMailSender mailSender) {
        return new ObjectProvider<>() {
            @Override
            public JavaMailSender getObject(Object... args) {
                return mailSender;
            }

            @Override
            public JavaMailSender getIfAvailable() {
                return mailSender;
            }

            @Override
            public JavaMailSender getIfUnique() {
                return mailSender;
            }

            @Override
            public JavaMailSender getObject() {
                return mailSender;
            }
        };
    }
}
