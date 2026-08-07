package com.storeanalytics.integration.telegram.web;

import com.storeanalytics.notification.linking.TelegramUpdateCommand;
import com.storeanalytics.notification.linking.TelegramWebhookService;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("/api/integrations/telegram")
public class TelegramWebhookController {

    private final TelegramWebhookService service;

    public TelegramWebhookController(TelegramWebhookService service) {
        this.service = service;
    }

    @PostMapping("/{botCode}/webhook")
    ResponseEntity<Void> process(
            @PathVariable String botCode,
            @RequestBody TelegramWebhookUpdate update
    ) {
        service.process(botCode, command(update));
        return ResponseEntity.ok().build();
    }

    private TelegramUpdateCommand command(TelegramWebhookUpdate update) {
        if (update == null || update.updateId() == null) {
            throw new IllegalArgumentException("Telegram update_id is required");
        }
        TelegramUpdateCommand.TelegramMessageCommand message = null;
        TelegramWebhookUpdate.TelegramMessage source = update.message();
        if (source != null && source.from() != null && source.chat() != null
                && source.from().id() != null && source.chat().id() != null) {
            message = new TelegramUpdateCommand.TelegramMessageCommand(
                    source.from().id(),
                    Boolean.TRUE.equals(source.from().bot()),
                    source.chat().id(),
                    source.chat().type(),
                    source.text()
            );
        }
        TelegramUpdateCommand.TelegramMembershipCommand membership = null;
        TelegramWebhookUpdate.TelegramChatMemberUpdated membershipSource =
                update.myChatMember();
        if (membershipSource != null && membershipSource.chat() != null
                && membershipSource.chat().id() != null) {
            membership = new TelegramUpdateCommand.TelegramMembershipCommand(
                    membershipSource.chat().id(),
                    membershipSource.chat().type(),
                    status(membershipSource.oldChatMember()),
                    status(membershipSource.newChatMember())
            );
        }
        return new TelegramUpdateCommand(
                update.updateId(),
                message,
                membership
        );
    }

    private String status(TelegramWebhookUpdate.TelegramChatMember member) {
        return member == null ? null : member.status();
    }
}
