package com.iblog.module.paper.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 模拟盘改名请求。
 */
@Data
public class PaperAccountRenameRequest {

    @NotBlank(message = "账户名称不能为空")
    private String name;
}
