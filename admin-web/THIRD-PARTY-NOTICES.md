# RuoYi-Vue3 来源

`src/api/login.js` 复用 [RuoYi-Vue3 的认证 API](https://github.com/yangzongzhuan/RuoYi-Vue3/blob/465b3ed4c2ab/src/api/login.js) 中的 `login`、`getInfo` 和 `logout`。验证码 `getCodeImg` 及 `src/api/menu.js` 的 `getRouters` 参照上游 `36fed1921a87` 快照中的 [认证 API](https://github.com/yangzongzhuan/RuoYi-Vue3/blob/36fed1921a87/src/api/login.js) 和 [路由 API](https://github.com/yangzongzhuan/RuoYi-Vue3/blob/36fed1921a87/src/api/menu.js)。接口沿用上游请求方法和包装函数结构，通过 CampusAI 请求模块显式传入会话令牌。

MIT 版权及许可见 [LICENSE-RuoYi-Vue3](LICENSE-RuoYi-Vue3)。
