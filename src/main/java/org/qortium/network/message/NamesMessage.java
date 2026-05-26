package org.qortium.network.message;

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import org.qortium.data.naming.NameData;
import org.qortium.data.network.LiteDataAnchor;
import org.qortium.naming.Name;
import org.qortium.transform.TransformationException;
import org.qortium.transform.Transformer;
import org.qortium.utils.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class NamesMessage extends Message {

	private static final int SIGNATURE_LENGTH = Transformer.SIGNATURE_LENGTH;

	private LiteDataResponseStatus status;
	private LiteDataAnchor anchor;
	private List<NameData> nameDataList;

	public NamesMessage(List<NameData> nameDataList, LiteDataAnchor anchor) {
		super(MessageType.NAMES);

		if (nameDataList == null)
			throw new IllegalArgumentException("Name data list is required for lite DATA response");

		this.status = LiteDataResponseStatus.DATA;
		this.anchor = anchor;
		this.nameDataList = nameDataList;

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		try {
			LiteDataMessageUtils.serializeStatusAndAnchor(bytes, this.status, this.anchor);

			bytes.write(Ints.toByteArray(nameDataList.size()));

			for (int i = 0; i < nameDataList.size(); ++i) {
				NameData nameData = nameDataList.get(i);

				Serialization.serializeSizedStringV2(bytes, nameData.getName());

				Serialization.serializeSizedStringV2(bytes, nameData.getReducedName());

				Serialization.serializeAddress(bytes, nameData.getOwner());

				Serialization.serializeSizedStringV2(bytes, nameData.getData());

				bytes.write(Longs.toByteArray(nameData.getRegistered()));

				Long updated = nameData.getUpdated();
				int wasUpdated = (updated != null) ? 1 : 0;
				bytes.write(Ints.toByteArray(wasUpdated));

				if (updated != null) {
					bytes.write(Longs.toByteArray(nameData.getUpdated()));
				}

				int isForSale = nameData.isForSale() ? 1 : 0;
				bytes.write(Ints.toByteArray(isForSale));

				if (nameData.isForSale()) {
					bytes.write(Longs.toByteArray(nameData.getSalePrice()));

					String saleRecipient = nameData.getSaleRecipient();
					bytes.write(Ints.toByteArray(saleRecipient != null ? 1 : 0));

					if (saleRecipient != null)
						Serialization.serializeAddress(bytes, saleRecipient);
				}

				bytes.write(nameData.getReference());

				bytes.write(Ints.toByteArray(nameData.getCreationGroupId()));
			}

		} catch (IOException e) {
			throw new AssertionError("IOException shouldn't occur with ByteArrayOutputStream");
		}

		this.dataBytes = bytes.toByteArray();
		this.checksumBytes = Message.generateChecksum(this.dataBytes);
	}

	private NamesMessage(LiteDataResponseStatus status, LiteDataAnchor anchor) {
		super(MessageType.NAMES);

		if (status != LiteDataResponseStatus.UNKNOWN)
			throw new IllegalArgumentException("Only UNKNOWN responses can omit name data");

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

	private NamesMessage(int id, LiteDataResponseStatus status, LiteDataAnchor anchor, List<NameData> nameDataList) {
		super(id, MessageType.NAMES);

		this.status = status;
		this.anchor = anchor;
		this.nameDataList = nameDataList;
	}

	public static NamesMessage unknown(LiteDataAnchor anchor) {
		return new NamesMessage(LiteDataResponseStatus.UNKNOWN, anchor);
	}

	public LiteDataResponseStatus getStatus() {
		return this.status;
	}

	public LiteDataAnchor getAnchor() {
		return this.anchor;
	}

	public List<NameData> getNameDataList() {
		return this.nameDataList;
	}


	public static Message fromByteBuffer(int id, ByteBuffer bytes) throws MessageException {
		try {
			LiteDataResponseStatus status = LiteDataMessageUtils.deserializeStatus(bytes);
			LiteDataAnchor anchor = LiteDataMessageUtils.deserializeAnchor(bytes);

			if (status == LiteDataResponseStatus.UNKNOWN) {
				if (bytes.hasRemaining())
					throw new BufferUnderflowException();

				return new NamesMessage(id, status, anchor, null);
			}

			final int nameCount = bytes.getInt();

			List<NameData> nameDataList = new ArrayList<>(nameCount);

			for (int i = 0; i < nameCount; ++i) {
				String name = Serialization.deserializeSizedStringV2(bytes, Name.MAX_NAME_SIZE);

				String reducedName = Serialization.deserializeSizedStringV2(bytes, Name.MAX_NAME_SIZE);

				String owner = Serialization.deserializeAddress(bytes);

				String data = Serialization.deserializeSizedStringV2(bytes, Name.MAX_DATA_SIZE);

				long registered = bytes.getLong();

				int wasUpdated = bytes.getInt();

				Long updated = null;
				if (wasUpdated == 1) {
					updated = bytes.getLong();
				}

				boolean isForSale = (bytes.getInt() == 1);

				Long salePrice = null;
				String saleRecipient = null;
				if (isForSale) {
					salePrice = bytes.getLong();

					boolean hasSaleRecipient = bytes.getInt() == 1;
					if (hasSaleRecipient)
						saleRecipient = Serialization.deserializeAddress(bytes);
				}

				byte[] reference = new byte[SIGNATURE_LENGTH];
				bytes.get(reference);

				int creationGroupId = bytes.getInt();

				NameData nameData = new NameData(name, reducedName, owner, data, registered, updated,
						isForSale, salePrice, saleRecipient, reference, creationGroupId);
				nameDataList.add(nameData);
			}

			if (bytes.hasRemaining()) {
				throw new BufferUnderflowException();
			}

			return new NamesMessage(id, status, anchor, nameDataList);

		} catch (TransformationException e) {
			throw new MessageException(e.getMessage(), e);
		}
	}

	public NamesMessage cloneWithNewId(int newId) {
		NamesMessage clone = this.status == LiteDataResponseStatus.UNKNOWN
				? NamesMessage.unknown(this.anchor)
				: new NamesMessage(this.nameDataList, this.anchor);
		clone.setId(newId);
		return clone;
	}

}
