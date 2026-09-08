package com.dsmarket.modules.user.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileRequest {

    @Size(max = 50, message = "昵称最长50字")
    private String nickname;

    private String avatar;

    private Integer gender;
}
