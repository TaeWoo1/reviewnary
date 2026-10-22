package com.sellerops.vendor.mail;

/** One plain-text mail to one person. */
public record OutboundMail(String to, String subject, String text) {}
