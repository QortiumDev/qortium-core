package org.qortium.data.transaction;

import org.qortium.transaction.Transaction.TransactionType;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

@XmlAccessorType(XmlAccessType.FIELD)
public class TransactionConfirmationTimingData {

	private TransactionType transactionType;
	private int transactionTypeValue;
	private int currentHeight;
	private int candidateHeight;
	private boolean transactionConfirmable;
	private boolean confirmableAtCandidateHeight;
	private Integer firstConfirmableHeight;
	private Integer confirmationDelayBlocks;
	private String delayReason;

	protected TransactionConfirmationTimingData() {
	}

	public TransactionConfirmationTimingData(TransactionType transactionType, int currentHeight, int candidateHeight,
			boolean transactionConfirmable, boolean confirmableAtCandidateHeight, Integer firstConfirmableHeight,
			Integer confirmationDelayBlocks, String delayReason) {
		this.transactionType = transactionType;
		this.transactionTypeValue = transactionType == null ? 0 : transactionType.value;
		this.currentHeight = currentHeight;
		this.candidateHeight = candidateHeight;
		this.transactionConfirmable = transactionConfirmable;
		this.confirmableAtCandidateHeight = confirmableAtCandidateHeight;
		this.firstConfirmableHeight = firstConfirmableHeight;
		this.confirmationDelayBlocks = confirmationDelayBlocks;
		this.delayReason = delayReason;
	}

	public TransactionType getTransactionType() {
		return this.transactionType;
	}

	public int getTransactionTypeValue() {
		return this.transactionTypeValue;
	}

	public int getCurrentHeight() {
		return this.currentHeight;
	}

	public int getCandidateHeight() {
		return this.candidateHeight;
	}

	public boolean isTransactionConfirmable() {
		return this.transactionConfirmable;
	}

	public boolean isConfirmableAtCandidateHeight() {
		return this.confirmableAtCandidateHeight;
	}

	public Integer getFirstConfirmableHeight() {
		return this.firstConfirmableHeight;
	}

	public Integer getConfirmationDelayBlocks() {
		return this.confirmationDelayBlocks;
	}

	public String getDelayReason() {
		return this.delayReason;
	}
}
