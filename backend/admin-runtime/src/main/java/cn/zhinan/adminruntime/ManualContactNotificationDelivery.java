package cn.zhinan.adminruntime;

import cn.zhinan.adminbooking.port.BookingPorts.DeliveryOutcome;
import cn.zhinan.adminbooking.port.BookingPorts.NotificationDelivery;
import cn.zhinan.adminbooking.port.BookingPorts.OutboxMessage;

/**
 * Records a staff follow-up. It does not call a provider and does not mark the customer reached.
 */
final class ManualContactNotificationDelivery implements NotificationDelivery {
  @Override
  public DeliveryOutcome deliver(OutboxMessage message) {
    return DeliveryOutcome.staffHold();
  }
}
