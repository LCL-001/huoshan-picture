package com.lcl.yunpicturebackend.service;

import static com.lcl.yunpicturebackend.utils.DesensitizeUtils.maskEmail;

import javax.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 邮件发送服务
 * <p>异步发送，失败仅记录日志：验证码已先行写入 Redis，发送失败不阻塞主流程，
 * 用户侧表现为收不到邮件可重新获取。</p>
 */
@Slf4j
@Service
public class MailSendService {

    @Resource
    private JavaMailSender javaMailSender;

    @Value("${spring.mail.username:}")
    private String from;

    /**
     * 异步发送验证码邮件
     *
     * @param to   收件邮箱
     * @param code 验证码
     */
    @Async
    public void sendCaptchaMailAsync(String to, String code) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject("火山图库验证码");
            message.setText("【火山图库】您的验证码是：" + code + "，5 分钟内有效。\n"
                    + "如非本人操作，请忽略本邮件。请勿将验证码泄露给他人。");
            javaMailSender.send(message);
            log.info("验证码邮件已发送 to={}", maskEmail(to));
        } catch (Exception e) {
            log.error("验证码邮件发送失败 to={}", maskEmail(to), e);
        }
    }
}
