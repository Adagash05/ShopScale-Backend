package com.amsati.shopscale.partners;

import com.amsati.shopscale.team.EmailService;
import com.amsati.shopscale.team.EmailTemplates;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Partner emails. All go through the platform's one EmailService, after the surrounding transaction commits, so a mail problem can never
 * undo or hold up a financial change. Delivery failures are logged there.
 */
@Component
@RequiredArgsConstructor
public class PartnerMail {
    private final EmailService email;
    private final PartnerAccess access;
    @Value("${app.frontend-url:http://localhost:5173}") private String frontendUrl;

    private String portal(String path) { return frontendUrl.replaceAll("/+$", "") + "/partners/" + path; }

    private void to(String address, String name, String subject, String heading, List<String> lines, String button, String link) {
        if (address == null || address.isBlank()) return;
        var m = EmailTemplates.notice(subject, heading, name, lines, button, link);
        email.sendAfterCommit(address, m.subject(), m.text(), m.html());
    }

    private void toAdmins(String subject, String heading, List<String> lines, String link) {
        for (String a : access.adminEmails()) to(a, "team", subject, heading, lines, "Open ShopScale Partners", link);
    }

    static String naira(BigDecimal v) { return "NGN " + String.format("%,.2f", v); }

    public void applicationSubmitted(String addr, String name) {
        to(addr, name, "We received your ShopScale Partners application", "Application received",
            List.of("Thank you for applying to ShopScale Partners. Our team reviews every application and will email you with the decision. You can check the status any time in your partner account."), "View your application", portal("dashboard"));
    }
    public void applicationForAdmins(String applicant) {
        toAdmins("New ShopScale Partners application", "New application", List.of(applicant + " applied to become a partner and is waiting for review."), frontendUrl.replaceAll("/+$", "") + "/admin/partners/applications");
    }
    public void approved(String addr, String name, String code) {
        to(addr, name, "You are now a ShopScale Partner", "Application approved",
            List.of("Your application was approved. Your referral code is " + code + ". Share your personal link from your dashboard; you earn commission on eligible subscription payments from businesses that register through it."), "Open your dashboard", portal("dashboard"));
    }
    public void rejected(String addr, String name, String reason) {
        to(addr, name, "Your ShopScale Partners application", "Application update",
            List.of("We are not able to approve your application at this time." + (reason == null || reason.isBlank() ? "" : " Reason: " + reason), "You are welcome to apply again later."), null, null);
    }
    public void suspended(String addr, String name, String reason) {
        to(addr, name, "Your ShopScale Partners account is suspended", "Account suspended",
            List.of("Your partner account has been suspended, so new referrals are not tracked and withdrawals are paused." + (reason == null || reason.isBlank() ? "" : " Reason: " + reason), "Reply to this email thread or contact ShopScale support if you think this is a mistake."), null, null);
    }
    public void reactivated(String addr, String name) {
        to(addr, name, "Your ShopScale Partners account is active again", "Account reactivated", List.of("Your partner account is active again."), "Open your dashboard", portal("dashboard"));
    }
    public void commissionEarned(String addr, String name, BigDecimal amount, BigDecimal payment, int holdingDays) {
        to(addr, name, "New ShopScale commission: " + naira(amount), "You earned a commission",
            List.of("A business you referred paid " + naira(payment) + " and you earned " + naira(amount) + ". It is held for " + holdingDays + " days and then becomes available to withdraw."), "View commissions", portal("commissions"));
    }
    public void commissionsAvailable(String addr, String name, BigDecimal total, int count) {
        to(addr, name, "Commission available to withdraw", "Commission is now available",
            List.of(count + (count == 1 ? " commission" : " commissions") + " totalling " + naira(total) + " finished the holding period and " + (count == 1 ? "is" : "are") + " now available."), "Open your dashboard", portal("withdrawals"));
    }
    public void withdrawalRequested(String addr, String name, BigDecimal amount, String applicantForAdmins) {
        to(addr, name, "Withdrawal request received", "Withdrawal requested", List.of("We received your withdrawal request for " + naira(amount) + ". We will review it and update you."), "View withdrawals", portal("withdrawals"));
        toAdmins("Withdrawal request: " + naira(amount), "Withdrawal requested", List.of(applicantForAdmins + " requested a withdrawal of " + naira(amount) + "."), frontendUrl.replaceAll("/+$", "") + "/admin/partners/withdrawals");
    }
    public void withdrawalApproved(String addr, String name, BigDecimal amount) {
        to(addr, name, "Withdrawal approved", "Withdrawal approved", List.of("Your withdrawal of " + naira(amount) + " was approved. We will send the bank transfer and confirm when it is done."), "View withdrawals", portal("withdrawals"));
    }
    public void withdrawalRejected(String addr, String name, BigDecimal amount, String reason) {
        to(addr, name, "Withdrawal not approved", "Withdrawal rejected", List.of("Your withdrawal of " + naira(amount) + " was not approved." + (reason == null ? "" : " Reason: " + reason), "The amount is back in your available balance."), "View withdrawals", portal("withdrawals"));
    }
    public void withdrawalProcessing(String addr, String name, BigDecimal amount) {
        to(addr, name, "Your payout is being processed", "Payout processing", List.of("We are processing the bank transfer of " + naira(amount) + " to your saved bank account."), "View withdrawals", portal("withdrawals"));
    }
    public void withdrawalPaid(String addr, String name, BigDecimal amount, String reference) {
        to(addr, name, "Payout completed: " + naira(amount), "Payout completed", List.of("We sent " + naira(amount) + " to your bank account. Transfer reference: " + reference + "."), "View withdrawals", portal("withdrawals"));
    }
    public void withdrawalFailed(String addr, String name, BigDecimal amount, String reason) {
        to(addr, name, "Payout failed", "Payout failed", List.of("The bank transfer of " + naira(amount) + " could not be completed." + (reason == null ? "" : " Reason: " + reason), "The amount is back in your available balance. Please check your bank details and request again."), "Check bank details", portal("payment-details"));
    }
}
