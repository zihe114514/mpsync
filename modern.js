/**
 * @name 测试音乐源
 * @description 现代语法探针
 * @version 1.0.0
 * @author probe
 * @homepage https://example.com
 */
const { EVENT_NAMES, request, on, send, utils } = globalThis.lx

class Api {
  #secret = 'p'
  constructor(name) { this.name = name }
  get label() { return `api:${this.name}` }
  static of(n) { return new Api(n) }
  async musicUrl(musicInfo, quality) {
    const { songmid, ...rest } = musicInfo
    const id = songmid ?? musicInfo?.meta?.songId ?? 'x'
    const body = await this.http(`https://example.com/u?id=${id}&q=${quality}`)
    let best = null
    for (const item of (body?.data ?? [])) {
      if (!best || item.br > best.br) best = item
    }
    best ||= { url: body?.url }
    return best.url ?? Promise.reject(new Error('no url'))
  }
  http(url, options = {}) {
    return new Promise((resolve, reject) => {
      request(url, { method: 'get', ...options }, (err, resp) => {
        if (err) return reject(err)
        resolve(resp.body)
      })
    })
  }
}

const apis = { wy: Api.of('wy'), tx: Api.of('tx'), kg: Api.of('kg') }

on(EVENT_NAMES.request, ({ source, action, info }) => {
  switch (action) {
    case 'musicUrl':
      return apis[source].musicUrl(info.musicInfo, info.type)
    default:
      return Promise.reject(new Error(`unsupported: ${action}`))
  }
})

send(EVENT_NAMES.inited, {
  openDevTools: false,
  sources: Object.fromEntries(Object.keys(apis).map(k => [k, {
    name: `${k} 源`,
    type: 'music',
    actions: ['musicUrl'],
    qualitys: ['128k', '320k', 'flac'],
  }])),
})