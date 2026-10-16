package com.fcms.service;

import com.fcms.dto.QuickCollectionRow;
import com.fcms.model.Customer;
import com.fcms.model.CustomerStatus;
import com.fcms.model.FinanceType;
import com.fcms.model.Payment;
import com.fcms.model.PaymentType;
import com.fcms.repository.CustomerRepository;
import com.fcms.repository.PaymentRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final PaymentRepository paymentRepository;
    private final AuditService auditService;

    public CustomerService(CustomerRepository customerRepository, PaymentRepository paymentRepository, AuditService auditService) {
        this.customerRepository = customerRepository;
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
    }

    public List<Customer> getAll() {
        return customerRepository.findAll();
    }

    public Optional<Customer> getById(Long id) {
        return customerRepository.findById(id);
    }

    public Customer create(Customer c) {
        if (c.getGroupKey() == null || c.getGroupKey().isBlank()) {
            c.setGroupKey(slug(c.getName()));
        } else {
            c.setGroupKey(slug(c.getGroupKey()));
        }
        recomputeDerived(c);
        c.setCreatedAt(LocalDate.now().atStartOfDay());
        c.setPaidInstallments(c.getPaidInstallments() == null ? 0 : c.getPaidInstallments());
        c.setTotalPaid(c.getTotalPaid() == null ? 0.0 : c.getTotalPaid());
        recomputeDerived(c);
        if (c.getStatus() == null) c.setStatus(CustomerStatus.Running);
        return customerRepository.save(c);
    }

    /**
     * Normalizes a name/group label into a stable lookup key (lowercase, trimmed,
     * spaces collapsed to single hyphens) so "Ranjith Kumar" and "ranjith  kumar" group together.
     */
    public static String slug(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
    }

    /** All loan accounts (including this one) that belong to the same real-world person. */
    public List<Customer> getLoansForGroup(String groupKey) {
        if (groupKey == null || groupKey.isBlank()) return List.of();
        return customerRepository.findAllByGroupKeyOrderByStartDateAsc(groupKey);
    }

    public Customer update(Long id, Customer updated, String editedBy, String reason) {
        Customer existing = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + id));

        auditField(existing, updated, "name", existing.getName(), updated.getName(), editedBy, reason);
        auditField(existing, updated, "mobile", existing.getMobile(), updated.getMobile(), editedBy, reason);
        auditField(existing, updated, "financeAmount", existing.getFinanceAmount(), updated.getFinanceAmount(), editedBy, reason);
        auditField(existing, updated, "interest", existing.getInterest(), updated.getInterest(), editedBy, reason);
        auditField(existing, updated, "installmentAmount", existing.getInstallmentAmount(), updated.getInstallmentAmount(), editedBy, reason);
        auditField(existing, updated, "totalInstallments", existing.getTotalInstallments(), updated.getTotalInstallments(), editedBy, reason);
        auditField(existing, updated, "totalPaid", existing.getTotalPaid(), updated.getTotalPaid(), editedBy, reason);
        auditField(existing, updated, "paidInstallments", existing.getPaidInstallments(), updated.getPaidInstallments(), editedBy, reason);
        auditField(existing, updated, "startDate", existing.getStartDate(), updated.getStartDate(), editedBy, reason);
        auditField(existing, updated, "status", existing.getStatus(), updated.getStatus(), editedBy, reason);

        existing.setName(updated.getName());
        existing.setMobile(updated.getMobile());
        existing.setAlternateMobile(updated.getAlternateMobile());
        existing.setAddress(updated.getAddress());
        if (updated.getGroupKey() != null && !updated.getGroupKey().isBlank()) {
            existing.setGroupKey(slug(updated.getGroupKey()));
        } else if (existing.getGroupKey() == null || existing.getGroupKey().isBlank()) {
            existing.setGroupKey(slug(existing.getName()));
        }
        existing.setFinanceAmount(updated.getFinanceAmount());
        existing.setInterest(updated.getInterest());
        existing.setStartDate(updated.getStartDate());
        existing.setFinanceType(updated.getFinanceType());
        existing.setCollectionDay(updated.getCollectionDay());
        existing.setInstallmentAmount(updated.getInstallmentAmount());
        existing.setTotalInstallments(updated.getTotalInstallments());
        // Total Paid / Installments Paid are normally only ever changed by recording payments,
        // but an admin can correct them directly here too (e.g. fixing a data-entry mistake) —
        // only applied when the caller actually sent a value, so a payload that omits them
        // (or an older client) never silently zeroes out real collection history.
        if (updated.getTotalPaid() != null) existing.setTotalPaid(updated.getTotalPaid());
        if (updated.getPaidInstallments() != null) existing.setPaidInstallments(updated.getPaidInstallments());
        if (updated.getStatus() != null) existing.setStatus(updated.getStatus());

        recomputeDerived(existing);
        return customerRepository.save(existing);
    }

    private void auditField(Customer existing, Customer updated, String field, Object oldV, Object newV, String editedBy, String reason) {
        if (newV != null && !newV.equals(oldV)) {
            auditService.log("Customer", existing.getId(), existing.getName(), field, oldV, newV, editedBy, reason);
        }
    }

    public void delete(Long id) {
        customerRepository.deleteById(id);
    }

    /**
     * Marks a fully-collected (Completed) loan as Closed. Closed loans are excluded from
     * "Running" counts/totals everywhere in the app (dashboard, Quick Collection, overdue
     * lists) — this is the final step once every installment has actually been collected.
     */
    public Customer closeAccount(Long id, String editedBy, String reason) {
        Customer c = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + id));
        if (c.getPendingAmount() != null && c.getPendingAmount() > 0) {
            throw new IllegalStateException("Cannot close this account — it still has a pending balance of " + c.getPendingAmount());
        }
        auditService.log("Customer", c.getId(), c.getName(), "status", c.getStatus(), CustomerStatus.Closed, editedBy, reason);
        c.setStatus(CustomerStatus.Closed);
        return customerRepository.save(c);
    }

    public void recomputeDerived(Customer c) {
        // Every loan defaults to a 100-installment schedule (e.g. Rs. 1,00,000 => Rs. 1,000/day
        // for 100 days) unless the caller explicitly set a different totalInstallments.
        if (c.getTotalInstallments() == null || c.getTotalInstallments() <= 0) {
            c.setTotalInstallments(100);
        }

        double financeAmount = c.getFinanceAmount() == null ? 0 : c.getFinanceAmount();
        double interest = c.getInterest() == null ? 0 : c.getInterest();
        double totalAmount = financeAmount + (financeAmount * interest / 100.0);
        c.setTotalAmount(totalAmount);

        if (c.getInstallmentAmount() == null || c.getInstallmentAmount() <= 0) {
            c.setInstallmentAmount(Math.round((totalAmount / c.getTotalInstallments()) * 100.0) / 100.0);
        }

        double totalPaid = c.getTotalPaid() == null ? 0 : c.getTotalPaid();
        double pending = Math.max(0, totalAmount - totalPaid);
        c.setPendingAmount(pending);
        c.setCurrentBalance(pending);

        if (c.getNextDueDate() == null && c.getStartDate() != null) {
            c.setNextDueDate(computeNextDueDate(c.getStartDate(), c.getFinanceType()));
        }

        if (c.getStartDate() != null && c.getTotalInstallments() != null) {
            c.setEndDate(computeNextDueDate(c.getStartDate(), c.getFinanceType(), c.getTotalInstallments()));
        }

        if (pending <= 0 && c.getStatus() != CustomerStatus.Closed) {
            c.setStatus(CustomerStatus.Completed);
        }
    }

    public LocalDate computeNextDueDate(LocalDate from, FinanceType type) {
        return computeNextDueDate(from, type, 1);
    }

    /**
     * Advances the given date by `periods` installment periods (days for Daily,
     * weeks for Weekly). Used for Advance payments that cover multiple installments.
     */
    public LocalDate computeNextDueDate(LocalDate from, FinanceType type, int periods) {
        if (periods < 1) periods = 1;
        if (type == FinanceType.Weekly) {
            return from.plusWeeks(periods);
        }
        return from.plusDays(periods);
    }

    /** Indian Standard Time — the day rolls over (and today's collections appear) at 12:00 AM IST, regardless of the server's own timezone. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Customers whose next payment is due today or is overdue (nextDueDate < today),
     * restricted to Running status. Used for the "Quick Collection" due-today list.
     *
     * "Today" is always computed in IST so the list refreshes at 12:00 AM India time no
     * matter where the backend server itself is hosted/deployed. Overdue loans (nextDueDate
     * before today) and loans due exactly today are both shown as soon as the day starts.
     *
     * Once a loan has ANY payment recorded today — Paid, Partial, NotPaid, or Advance — it
     * drops off this list immediately, even if (for Partial/NotPaid) nextDueDate didn't
     * advance. It only reappears after the day rolls over at 12:00 AM IST.
     */
    public List<Customer> getDueToday() {
        LocalDate today = LocalDate.now(IST);
        Set<Long> alreadyMarkedToday = paymentRepository.findByDate(today).stream()
                .map(Payment::getCustomerId)
                .collect(Collectors.toSet());
        return customerRepository.findAll().stream()
                .filter(c -> c.getStatus() == CustomerStatus.Running)
                .filter(c -> c.getNextDueDate() != null && !c.getNextDueDate().isAfter(today))
                .filter(c -> !alreadyMarkedToday.contains(c.getId()))
                .toList();
    }

    /**
     * Quick Collection rows for one date.
     *
     * - Today: every Running loan that is due/overdue OR already has a payment recorded today.
     * - Any PAST date: every loan that has a payment on that date PLUS every loan that was
     *   scheduled to be collected that day but was never marked (paymentId == null), so missed
     *   entries can be filled in later with Paid / Partial / Not Paid / Advance exactly like today.
     *   A loan is "scheduled" on a past date when the date is on/after its start date, falls on
     *   its weekly collection slot (Weekly loans), the loan was still active that day, and the
     *   day was not already covered by an earlier Advance payment. Because a Running loan keeps
     *   a slot for every day until it is fully collected, days beyond the planned 100 (or N)
     *   installments appear automatically as "extended" days.
     * - Future dates: only loans with a payment recorded on that date.
     */
    public List<QuickCollectionRow> getQuickCollection(LocalDate date) {
        LocalDate today = LocalDate.now(IST);
        Map<Long, Payment> paymentsOnDate = paymentRepository.findByDate(date).stream()
                .collect(Collectors.toMap(Payment::getCustomerId, p -> p, (a, b) -> a));

        List<Customer> all = customerRepository.findAll();
        List<Customer> rows;
        if (date.equals(today)) {
            rows = all.stream()
                    .filter(c -> c.getStatus() == CustomerStatus.Running)
                    .filter(c -> paymentsOnDate.containsKey(c.getId())
                            || (c.getNextDueDate() != null && !c.getNextDueDate().isAfter(today)))
                    .toList();
        } else if (date.isBefore(today)) {
            Map<Long, Set<LocalDate>> covered = advanceCoveredDates(all);
            rows = all.stream()
                    .filter(c -> paymentsOnDate.containsKey(c.getId())
                            || (isScheduledOn(c, date)
                                && !covered.getOrDefault(c.getId(), Set.of()).contains(date)))
                    .toList();
        } else {
            rows = all.stream()
                    .filter(c -> paymentsOnDate.containsKey(c.getId()))
                    .toList();
        }

        return rows.stream()
                .sorted(Comparator.comparing(Customer::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(c -> {
                    QuickCollectionRow row = new QuickCollectionRow();
                    row.setCustomerId(c.getId());
                    row.setName(c.getName());
                    row.setMobile(c.getMobile());
                    row.setGroupKey(c.getGroupKey());
                    row.setFinanceAmount(c.getFinanceAmount());
                    row.setInstallmentAmount(c.getInstallmentAmount());
                    row.setFinanceType(c.getFinanceType());
                    row.setNextDueDate(c.getNextDueDate());
                    row.setStatus(c.getStatus());
                    row.setTotalInstallments(c.getTotalInstallments());
                    int slot = slotNumber(c, date);
                    if (slot > 0) {
                        row.setInstallmentNo(slot);
                        row.setExtended(c.getTotalInstallments() != null && slot > c.getTotalInstallments());
                    }
                    Payment p = paymentsOnDate.get(c.getId());
                    if (p != null) {
                        row.setPaymentId(p.getId());
                        row.setPaymentType(p.getType());
                        row.setPaymentAmount(p.getAmount());
                        row.setNotes(p.getNotes());
                        row.setCollectedBy(p.getCollectedBy());
                    }
                    return row;
                })
                .toList();
    }

    /** 1-based installment slot a date represents for a loan (0 if before start or off a weekly slot). */
    public int slotNumber(Customer c, LocalDate date) {
        if (c.getStartDate() == null || date.isBefore(c.getStartDate())) return 0;
        long days = ChronoUnit.DAYS.between(c.getStartDate(), date);
        if (c.getFinanceType() == FinanceType.Weekly) {
            return days % 7 == 0 ? (int) (days / 7) + 1 : 0;
        }
        return (int) days + 1;
    }

    /**
     * Was this loan expected to be collected on a (past) date? Running loans keep a slot every
     * day/week, so the schedule extends past totalInstallments until the loan is paid off.
     * Completed / Closed loans only count up to their last recorded payment date.
     */
    private boolean isScheduledOn(Customer c, LocalDate date) {
        if (slotNumber(c, date) == 0) return false;
        if (c.getStatus() == CustomerStatus.Running) return true;
        return c.getLastPaymentDate() != null && !date.isAfter(c.getLastPaymentDate());
    }

    /**
     * Dates already paid for by an Advance payment that covered more than one installment
     * (the advance's own date is not included — it has its own payment row). These days must
     * not show up as "unmarked" on Quick Collection.
     */
    private Map<Long, Set<LocalDate>> advanceCoveredDates(List<Customer> customers) {
        Map<Long, Customer> byId = customers.stream().collect(Collectors.toMap(Customer::getId, c -> c, (a, b) -> a));
        Map<Long, Set<LocalDate>> out = new java.util.HashMap<>();
        for (Payment p : paymentRepository.findByType(PaymentType.Advance)) {
            Customer c = byId.get(p.getCustomerId());
            if (c == null || p.getDate() == null || p.getAmount() == null) continue;
            double inst = c.getInstallmentAmount() == null ? 0 : c.getInstallmentAmount();
            if (inst <= 0) continue;
            int periods = Math.max(1, (int) Math.floor(p.getAmount() / inst));
            for (int i = 1; i < periods; i++) {
                LocalDate d = c.getFinanceType() == FinanceType.Weekly ? p.getDate().plusWeeks(i) : p.getDate().plusDays(i);
                out.computeIfAbsent(c.getId(), k -> new java.util.HashSet<>()).add(d);
            }
        }
        return out;
    }

    /**
     * In-memory filtered search over all customers. All provided filters are combined with AND.
     */
    public List<Customer> search(String q, CustomerStatus status, FinanceType financeType,
                                  String paymentStatus, Boolean overdue) {
        LocalDate today = LocalDate.now(IST);
        String qLower = (q == null || q.isBlank()) ? null : q.trim().toLowerCase();

        return customerRepository.findAll().stream()
                .filter(c -> qLower == null
                        || (c.getName() != null && c.getName().toLowerCase().contains(qLower))
                        || (c.getMobile() != null && c.getMobile().toLowerCase().contains(qLower)))
                .filter(c -> status == null || c.getStatus() == status)
                .filter(c -> financeType == null || c.getFinanceType() == financeType)
                .filter(c -> {
                    // Filters by the type of the most recently recorded payment on the loan
                    // (Paid / Partial / NotPaid / Advance) — i.e. "who did we mark as X last time".
                    if (paymentStatus == null || paymentStatus.isBlank()) return true;
                    return paymentStatus.equalsIgnoreCase(c.getLastPaymentType());
                })
                .filter(c -> {
                    if (overdue == null) return true;
                    boolean isOverdue = c.getStatus() == CustomerStatus.Running
                            && c.getNextDueDate() != null && c.getNextDueDate().isBefore(today);
                    return overdue == isOverdue;
                })
                .toList();
    }
}
