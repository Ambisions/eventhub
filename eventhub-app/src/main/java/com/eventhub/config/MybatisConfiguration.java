package com.eventhub.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@MapperScan({
        "com.eventhub.user.mapper",
        "com.eventhub.event.mapper",
        "com.eventhub.inventory.mapper",
        "com.eventhub.order.mapper",
        "com.eventhub.ticket.mapper",
        "com.eventhub.notification.mapper"
})
public class MybatisConfiguration {
}
