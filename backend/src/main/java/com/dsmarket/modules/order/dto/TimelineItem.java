package com.dsmarket.modules.order.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class TimelineItem {

    private Integer status;
    private String name;
    private LocalDateTime time;
}
