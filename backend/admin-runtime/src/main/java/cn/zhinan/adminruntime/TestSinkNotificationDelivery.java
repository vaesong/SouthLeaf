package cn.zhinan.adminruntime;

import cn.zhinan.adminbooking.port.BookingPorts.DeliveryOutcome;
import cn.zhinan.adminbooking.port.BookingPorts.NotificationDelivery;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxMessage;

/** Explicit demo-only sink: it accepts facts locally and never makes a network call. */
final class TestSinkNotificationDelivery implements NotificationDelivery {
  @Override
  public DeliveryOutcome deliver(OutboxMessage message) {
    return DeliveryOutcome.success();
  }
}
