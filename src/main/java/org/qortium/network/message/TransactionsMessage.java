package org.qortium.network.message;

import com.google.common.primitives.Ints;
import org.qortium.data.network.LiteDataAnchor;
import org.qortium.data.transaction.TransactionData;
import org.qortium.transform.TransformationException;
import org.qortium.transform.transaction.TransactionTransformer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class TransactionsMessage extends Message {

	private LiteDataResponseStatus status;
	private LiteDataAnchor anchor;
	private List<TransactionData> transactions;

	public TransactionsMessage(List<TransactionData> transactions, LiteDataAnchor anchor) throws MessageException {
		super(MessageType.TRANSACTIONS);

		if (transactions == null)
			throw new IllegalArgumentException("Transactions list is required for lite DATA response");

		this.status = LiteDataResponseStatus.DATA;
		this.anchor = anchor;
		this.transactions = transactions;

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			LiteDataMessageUtils.serializeStatusAndAnchor(bytes, this.status, this.anchor);

			bytes.write(Ints.toByteArray(transactions.size()));

			for (int i = 0; i < transactions.size(); ++i) {
				TransactionData transactionData = transactions.get(i);

				byte[] serializedTransactionData = TransactionTransformer.toBytes(transactionData);
				bytes.write(serializedTransactionData);
			}

		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		} catch (TransformationException e) {
			throw new MessageException(e.getMessage(), e);
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private TransactionsMessage(LiteDataResponseStatus status, LiteDataAnchor anchor) {
		super(MessageType.TRANSACTIONS);

		if (status != LiteDataResponseStatus.UNKNOWN)
			throw new IllegalArgumentException("Only UNKNOWN responses can omit transactions");

		this.status = status;
		this.anchor = anchor;

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			LiteDataMessageUtils.serializeStatusAndAnchor(bytes, this.status, this.anchor);
		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private TransactionsMessage(int id, LiteDataResponseStatus status, LiteDataAnchor anchor, List<TransactionData> transactions) {
		super(id, MessageType.TRANSACTIONS);

		this.status = status;
		this.anchor = anchor;
		this.transactions = transactions;
	}

	public static TransactionsMessage unknown(LiteDataAnchor anchor) {
		return new TransactionsMessage(LiteDataResponseStatus.UNKNOWN, anchor);
	}

	public LiteDataResponseStatus getStatus() {
		return this.status;
	}

	public LiteDataAnchor getAnchor() {
		return this.anchor;
	}

	public List<TransactionData> getTransactions() {
		return this.transactions;
	}

	public static Message fromByteBuffer(int id, ByteBuffer byteBuffer) throws MessageException {
		try {
			LiteDataResponseStatus status = LiteDataMessageUtils.deserializeStatus(byteBuffer);
			LiteDataAnchor anchor = LiteDataMessageUtils.deserializeAnchor(byteBuffer);

			if (status == LiteDataResponseStatus.UNKNOWN) {
				if (byteBuffer.hasRemaining())
					throw new BufferUnderflowException();

				return new TransactionsMessage(id, status, anchor, null);
			}

			final int transactionCount = byteBuffer.getInt();

			List<TransactionData> transactions = new ArrayList<>();

			for (int i = 0; i < transactionCount; ++i) {
				TransactionData transactionData = TransactionTransformer.fromByteBuffer(byteBuffer);
				transactions.add(transactionData);
			}

			if (byteBuffer.hasRemaining()) {
				throw new BufferUnderflowException();
			}

			return new TransactionsMessage(id, status, anchor, transactions);

		} catch (TransformationException e) {
			throw new MessageException(e.getMessage(), e);
		}
	}

}
