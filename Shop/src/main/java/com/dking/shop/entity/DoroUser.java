package com.dking.shop.entity;


import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("doro_user")
public class DoroUser {
    @TableId
    private Long userId;
    private String userName;
    private String nickName;
    private String password;
    private String telephone;
    private String avatar;
    private Integer status;
    private boolean deleted;
    private Integer role;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    private LocalDateTime lastLoginTime;
}
