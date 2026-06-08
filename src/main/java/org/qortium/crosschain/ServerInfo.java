package org.qortium.crosschain;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import java.util.Objects;

@XmlAccessorType(XmlAccessType.FIELD)
public class ServerInfo {

        private long averageResponseTime;

        private String hostName;

        private int port;

        private String connectionType;

        private String certificateSha256Fingerprint;

        private boolean isCurrent;

        public ServerInfo() {
        }

        public ServerInfo(long averageResponseTime, String hostName, int port, String connectionType, boolean isCurrent) {
                this(averageResponseTime, hostName, port, connectionType, null, isCurrent);
        }

        public ServerInfo(long averageResponseTime, String hostName, int port, String connectionType, String certificateSha256Fingerprint, boolean isCurrent) {
                this.averageResponseTime = averageResponseTime;
                this.hostName = hostName;
                this.port = port;
                this.connectionType = connectionType;
                this.certificateSha256Fingerprint = certificateSha256Fingerprint;
                this.isCurrent = isCurrent;
        }

        public long getAverageResponseTime() {
                return averageResponseTime;
        }

        public String getHostName() {
                return hostName;
        }

        public int getPort() {
                return port;
        }

        public String getConnectionType() {
                return connectionType;
        }

        public String getCertificateSha256Fingerprint() {
                return certificateSha256Fingerprint;
        }

        public boolean isCurrent() {
                return isCurrent;
        }

        @Override
        public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                ServerInfo that = (ServerInfo) o;
                return averageResponseTime == that.averageResponseTime && port == that.port && isCurrent == that.isCurrent && Objects.equals(hostName, that.hostName) && Objects.equals(connectionType, that.connectionType) && Objects.equals(certificateSha256Fingerprint, that.certificateSha256Fingerprint);
        }

        @Override
        public int hashCode() {
                return Objects.hash(averageResponseTime, hostName, port, connectionType, certificateSha256Fingerprint, isCurrent);
        }

        @Override
        public String toString() {
                return "ServerInfo{" +
                        "averageResponseTime=" + averageResponseTime +
                        ", hostName='" + hostName + '\'' +
                        ", port=" + port +
                        ", connectionType='" + connectionType + '\'' +
                        ", certificateSha256Fingerprint='" + certificateSha256Fingerprint + '\'' +
                        ", isCurrent=" + isCurrent +
                        '}';
        }
}
