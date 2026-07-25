package com.usora.notification.service;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TwilioService {

    public void sendSms(String accountSid, String authToken, String fromNumber,
                        String toNumber, String body) {
        Twilio.init(accountSid, authToken);
        Message message = Message.creator(
                new PhoneNumber(toNumber),
                new PhoneNumber(fromNumber),
                body
        ).create();

        log.debug("Twilio SMS sent, SID: {}", message.getSid());
    }
}
