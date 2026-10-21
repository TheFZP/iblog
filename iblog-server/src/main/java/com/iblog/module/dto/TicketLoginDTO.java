package com.iblog.module.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 官网用一次性票据换取登录态。
 */
@Data
public class TicketLoginDTO {

    @NotBlank(message = "登录票据不能为空")
    private String ticket;
}
