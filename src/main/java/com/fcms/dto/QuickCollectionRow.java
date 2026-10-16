package com.fcms.dto;

import com.fcms.model.CustomerStatus;
import com.fcms.model.FinanceType;
import com.fcms.model.PaymentType;

import java.time.LocalDate;

/**
 * One loan's row on the Quick Collection page for a given date — unifies "still due, unmarked"
 * and "already marked" loans into one shape so a single date-driven endpoint serves both live
 * same-day collection and viewing/editing any past day's collections.
 */
public class QuickCollectionRow {
    private Long customerId;
    private String name;
    private String mobile;
    private String groupKey;
    private Double financeAmount;
    private Double installmentAmount;
    private FinanceType financeType;
    private LocalDate nextDueDate;
    private CustomerStatus status;

    /** Null if this loan hasn't been marked yet on the viewed date. */
    private Long paymentId;
    private PaymentType paymentType;
    private Double paymentAmount;
    private String notes;
    private String collectedBy;

    /** Installment slot number this row's date represents for the loan (may exceed totalInstallments when the loan was auto-extended). */
    private Integer installmentNo;
    private Integer totalInstallments;
    /** True when installmentNo is beyond the planned schedule, i.e. an automatically extended day. */
    private boolean extended;

    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }
    public String getGroupKey() { return groupKey; }
    public void setGroupKey(String groupKey) { this.groupKey = groupKey; }
    public Double getFinanceAmount() { return financeAmount; }
    public void setFinanceAmount(Double financeAmount) { this.financeAmount = financeAmount; }
    public Double getInstallmentAmount() { return installmentAmount; }
    public void setInstallmentAmount(Double installmentAmount) { this.installmentAmount = installmentAmount; }
    public FinanceType getFinanceType() { return financeType; }
    public void setFinanceType(FinanceType financeType) { this.financeType = financeType; }
    public LocalDate getNextDueDate() { return nextDueDate; }
    public void setNextDueDate(LocalDate nextDueDate) { this.nextDueDate = nextDueDate; }
    public CustomerStatus getStatus() { return status; }
    public void setStatus(CustomerStatus status) { this.status = status; }
    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }
    public PaymentType getPaymentType() { return paymentType; }
    public void setPaymentType(PaymentType paymentType) { this.paymentType = paymentType; }
    public Double getPaymentAmount() { return paymentAmount; }
    public void setPaymentAmount(Double paymentAmount) { this.paymentAmount = paymentAmount; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getCollectedBy() { return collectedBy; }
    public void setCollectedBy(String collectedBy) { this.collectedBy = collectedBy; }
    public Integer getInstallmentNo() { return installmentNo; }
    public void setInstallmentNo(Integer installmentNo) { this.installmentNo = installmentNo; }
    public Integer getTotalInstallments() { return totalInstallments; }
    public void setTotalInstallments(Integer totalInstallments) { this.totalInstallments = totalInstallments; }
    public boolean isExtended() { return extended; }
    public void setExtended(boolean extended) { this.extended = extended; }
}
