package org.qortium.data.arbitrary;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;

@XmlAccessorType(XmlAccessType.FIELD)
public class ArbitraryServiceInfo {

    public String id;
    public int value;
    public Long maxSize;

    // "private" is a reserved word in Java, so rename the serialized field explicitly
    @XmlElement(name = "private")
    public boolean isPrivate;

    public boolean requiresEncryption;
    public boolean singleFile;
    public boolean supportsDirectories;
    public boolean requiresValidation;

    public ArbitraryServiceInfo() {

    }

}
