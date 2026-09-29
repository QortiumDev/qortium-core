package org.qortium.crosschain;
public final class SendTestCoordinator {
 public static ZcashFamilyNativeCoordinator create(ZcashFamilyNativeAdapter adapter) {
  return new ZcashFamilyNativeCoordinator(adapter, "Isolated ARRR send test");
 }
}
