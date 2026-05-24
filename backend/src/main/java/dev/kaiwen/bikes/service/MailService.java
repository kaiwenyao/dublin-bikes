package dev.kaiwen.bikes.service;

import dev.kaiwen.bikes.config.MailProperties;
import jakarta.mail.internet.MimeMessage;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

@Service
@RequiredArgsConstructor
@Slf4j
public class MailService {

    private static final String SUBJECT = "[Dublin Bikes] Verify your email to start riding";

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final TemplateEngine templateEngine;
    private final MailProperties mailProperties;

    @Value("${spring.mail.host:}")
    private String mailHost;

    @Async("mailExecutor")
    public void sendVerificationEmail(String email, String code, int expiresMinutes, String activationToken) {
        if (mailHost == null || mailHost.isBlank()) {
            log.info("mail not configured, skip");
            return;
        }
        if (mailProperties.from() == null || mailProperties.from().isBlank()) {
            log.info("mail not configured, skip");
            return;
        }
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            log.info("mail sender not configured, skip");
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("code", code);
            context.setVariable("expiresMinutes", expiresMinutes);
            String activationSection = buildActivationLinkSection(activationToken);
            if (activationSection != null) {
                context.setVariable("activationLinkSection", activationSection);
            }
            String html = templateEngine.process("email_verification", context);

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            String fromName = mailProperties.fromName() == null ? "Dublin Bikes" : mailProperties.fromName();
            helper.setFrom(mailProperties.from(), fromName);
            helper.setTo(email);
            helper.setSubject(SUBJECT);
            helper.setText(html, true);
            mailSender.send(message);
        } catch (Exception ex) {
            log.warn("failed to send verification email to {}", email, ex);
        }
    }

    private String buildActivationLinkSection(String activationToken) {
        String baseUrl = mailProperties.frontendBaseUrl();
        if (baseUrl == null || baseUrl.isBlank() || activationToken == null || activationToken.isBlank()) {
            return null;
        }
        String link = baseUrl.replaceAll("/$", "") + "/activate/" + activationToken;
        return "<p>Or activate via link: <a href=\"" + link + "\">" + link + "</a></p>";
    }
}
