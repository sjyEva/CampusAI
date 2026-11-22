# 学生端上游代码说明

学生端从 RuoYi-App 的认证和请求模块选取代码，适配 CampusAI 的登录、退出与课程接口。RuoYi-App 采用 MIT 许可，版权为 Copyright (c) 2022 若依。完整许可见 [LICENSE-RuoYi-App](LICENSE-RuoYi-App)。上游项目见 [GitHub](https://github.com/yangzongzhuan/RuoYi-App) 和 [Gitee](https://gitee.com/y_project/RuoYi-App)。

| CampusAI 文件 | RuoYi-App 对应文件 | 本地适配内容 |
| --- | --- | --- |
| `src/api/login.js` | [`api/login.js`](https://gitee.com/y_project/RuoYi-App/blob/master/api/login.js) | 保留登录、用户信息、退出与验证码接口封装，将登录参数改为凭证对象。 |
| `src/utils/auth.js` | [`utils/auth.js`](https://gitee.com/y_project/RuoYi-App/blob/master/utils/auth.js) | 保留基于 uni-app 本地存储的三个令牌操作，使用项目专属存储键。 |
| `src/utils/request.js` | [`utils/request.js`](https://gitee.com/y_project/RuoYi-App/blob/master/utils/request.js) | 保留统一请求入口及 Bearer 令牌约定，改用回调包裹、统一错误对象和有条件的过期令牌清理。 |
| `src/config.js` | [`config.js`](https://gitee.com/y_project/RuoYi-App/blob/master/config.js) | 保留集中配置 API 基址的形式，加入 H5 开发代理和微信小程序环境变量覆盖。 |

页面与路由、课程接口调用及开发代理配置由 CampusAI 编写。uni-app CLI 依赖组合参照 DCloud 官方 Vite 模板的 `3.0.0-5030420261029002` 版本，模板提交为 `f42e736`。[DCloud Vite 模板依赖](https://gitee.com/dcloud/uni-preset-vue/blob/vite/package.json)
