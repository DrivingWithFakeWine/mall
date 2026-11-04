// 批量注册压测用户、登录并确保有一条地址，输出 JMeter 用的 users.csv（token,addressId）。
// 用法：
//   node gen-users.mjs --base http://<host> --count 500 --out users.csv
// 说明：脚本可重复执行；已存在的用户会走登录，不会重复建地址。

import fs from 'node:fs'

const args = process.argv.slice(2)

function arg(name, fallback) {
    const index = args.indexOf(`--${name}`)
    return index >= 0 && args[index + 1] !== undefined ? args[index + 1] : fallback
}

const base = (arg('base', 'http://127.0.0.1')).replace(/\/$/, '')
const apiPrefix = arg('apiPrefix', '/api')
const count = Number(arg('count', '500'))
const start = Number(arg('start', '1'))
const concurrency = Number(arg('concurrency', '10'))
const password = arg('password', 'Loadtest123')
const outFile = arg('out', 'users.csv')

async function call(path, { method = 'GET', token, body } = {}) {
    const headers = { 'Content-Type': 'application/json' }
    if (token) headers.Authorization = `Bearer ${token}`
    const res = await fetch(base + apiPrefix + path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
    })
    const text = await res.text()
    let json
    try {
        json = JSON.parse(text)
    } catch {
        json = { code: res.status, message: text }
    }
    return { httpStatus: res.status, ...json }
}

function userOf(index) {
    return {
        username: `lt${String(index).padStart(6, '0')}`,
        telephone: `136${String(10_000_000 + index).padStart(8, '0')}`,
        nickname: `压测用户${index}`,
    }
}

async function ensureUser(index) {
    const user = userOf(index)
    let login = await call('/user/login', {
        method: 'POST',
        body: { username: user.username, password },
    })
    if (login.code !== 200) {
        const register = await call('/user/register', {
            method: 'POST',
            body: {
                username: user.username,
                nickname: user.nickname,
                password,
                telephone: user.telephone,
            },
        })
        if (register.code !== 200) {
            if (args.includes('--debug')) {
                console.error('register response', JSON.stringify(register))
            }
            throw new Error(`注册失败 ${user.username}: ${register.message}`)
        }
        login = await call('/user/login', {
            method: 'POST',
            body: { username: user.username, password },
        })
    }
    if (login.code !== 200 || !login.data?.accessToken) {
        throw new Error(`登录失败 ${user.username}: ${login.message}`)
    }

    const token = login.data.accessToken
    const addresses = await call('/address/list', { token })
    if (addresses.code !== 200) {
        throw new Error(`地址查询失败 ${user.username}: ${addresses.message}`)
    }
    let addressId = addresses.data?.[0]?.id
    if (!addressId) {
        const added = await call('/address/add', {
            method: 'POST',
            token,
            body: {
                receiverName: user.nickname,
                receiverPhone: user.telephone,
                province: '北京市',
                city: '北京市',
                district: '海淀区',
                detail: `压测地址 ${index} 号`,
                isDefault: 1,
            },
        })
        if (added.code !== 200) {
            throw new Error(`地址创建失败 ${user.username}: ${added.message}`)
        }
        const after = await call('/address/list', { token })
        addressId = after.data?.[0]?.id
    }
    if (!addressId) {
        throw new Error(`未拿到地址 ${user.username}`)
    }
    return { line: `${token},${addressId}` }
}

const indexes = Array.from({ length: count }, (_, i) => start + i)
const lines = new Array(count)
const failures = []
let done = 0

for (let offset = 0; offset < indexes.length; offset += concurrency) {
    const chunk = indexes.slice(offset, offset + concurrency)
    await Promise.all(chunk.map(async index => {
        try {
            const result = await ensureUser(index)
            lines[index - start] = result.line
        } catch (e) {
            failures.push(`index=${index}: ${e.message}`)
        } finally {
            done += 1
            if (done % 50 === 0 || done === count) {
                console.log(`进度 ${done}/${count}`)
            }
        }
    }))
}

fs.writeFileSync(outFile, lines.filter(Boolean).join('\n') + '\n', 'utf8')
console.log(`完成：成功 ${lines.filter(Boolean).length}，失败 ${failures.length}，输出 ${outFile}`)
if (failures.length > 0) {
    console.error(failures.slice(0, 10).join('\n'))
    process.exitCode = 1
}
