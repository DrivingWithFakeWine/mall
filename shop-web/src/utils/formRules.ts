import type { Rule } from 'antd/es/form'

export function confirmFieldRule(field: string, errorMsg = '两次输入不一致'): Rule {
    return ({ getFieldValue }) => ({
        validator(_, value) {
            if (!value || getFieldValue(field) === value) {
                return Promise.resolve()
            }
            return Promise.reject(new Error(errorMsg))
        },
    })
}

/** 手机号格式（值为空时 antd 自动跳过 pattern 校验，所以适用于"可空但填了必须合法"的字段） */
export const phoneRule: Rule = {
    pattern: /^1[3-9]\d{9}$/,
    message: '手机号格式不正确',
}

/** 用户名：4-16 位，字母开头，可含数字、下划线 */
export const usernameRule: Rule = {
    pattern: /^[a-zA-Z][a-zA-Z0-9_]{3,15}$/,
    message: '4-16 位，字母开头，可含数字和下划线',
}

/** 昵称：2-12 位英文、数字或汉字 */
export const nicknameRule: Rule = {
    pattern: /^[一-龥A-Za-z0-9]{2,12}$/,
    message: '昵称为 2-12 位英文、数字或汉字',
}