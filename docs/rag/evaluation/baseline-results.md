# CampusAI 开发者固定样例检索评估

运行时间：2026-11-23T02:30:24.436334700Z。

开发者为可复现功能验证自编的课程片段与问题，非教师课件，未由 F 独立标注或保管。acceptance 为预先固定的开发者验收样例，不能解释为独立盲测。

本次只评估真实解析及 BM25 检索，不代表生成答案正确率或真实模型供应商可用性。命中要求前三个片段包含标注原文且段落范围覆盖标注位置。无依据题单独统计，未计入 Hit@3 分母。

| 集合 | 有依据题 Hit@3 | 无依据题返回空结果 | 无依据题误召回 |
|---|---|---|---|
| development | 15/15 | 2/5 | 3 |
| acceptance | 15/15 | 4/5 | 1 |

参数：k1=1.2，b=0.75，最低检索词覆盖率=0.5，片段上限=700 字符，TopK=3。

片段数量：{course2=5, course1=5}。语料规模较小且由开发者自编，结果只证明本固定样例的行为，不外推到真实课程效果。

| 编号 | 集合 | 问题 | 有依据 | 命中 / 正确空结果 |
|---|---|---|---|---|
| C1-S01 | development | 计算机系统由哪些部分组成？ | 是 | true |
| C1-S02 | development | 一个字节通常包含多少比特？ | 是 | true |
| C1-S03 | development | 中央处理器处理机器指令通常经过哪些阶段？ | 是 | true |
| C1-S04 | development | 高速缓存利用哪两种局部性减少访存开销？ | 是 | true |
| C1-S05 | development | 操作系统主要管理哪些资源？ | 是 | true |
| C1-S06 | development | 同一进程中的线程通常共享哪些资源？ | 是 | true |
| C1-S07 | development | 分页系统用什么记录虚拟页与物理页框的对应关系？ | 是 | true |
| C1-S08 | development | 相对路径以哪个位置为起点解释？ | 是 | true |
| C1-S09 | acceptance | 网络分层对模块间依赖有什么帮助？ | 是 | true |
| C1-S10 | acceptance | 域名解析成功能否保证目标应用服务正常？ | 是 | true |
| C1-S11 | acceptance | 关系数据库主键值需要满足哪些约束？ | 是 | true |
| C1-S12 | acceptance | 数据库事务的原子性要求一组操作怎样完成？ | 是 | true |
| C1-S13 | acceptance | 算法时间复杂度是否直接预测精确运行秒数？ | 是 | true |
| C1-S14 | acceptance | 栈遵循什么访问规则？ | 是 | true |
| C1-S15 | acceptance | 哈希摘要是否是原文的可逆压缩副本？ | 是 | true |
| C2-S01 | development | 软件需求的验收标准应该怎样表达？ | 是 | true |
| C2-S02 | development | 用户故事能否替代全部交互细节和验收条件？ | 是 | true |
| C2-S03 | development | 产品待办列表通常依据哪些因素调整顺序？ | 是 | true |
| C2-S04 | development | 每日站会通常控制在多长时间以内？ | 是 | true |
| C2-S05 | development | 迭代回顾主要关注什么？ | 是 | true |
| C2-S06 | development | 使用版本控制分支是否会自动消除合并冲突？ | 是 | true |
| C2-S07 | development | 持续集成构建成功能够证明什么？ | 是 | true |
| C2-S08 | acceptance | 集成测试主要关注什么？ | 是 | true |
| C2-S09 | acceptance | 边界值分析重点检查哪些输入位置？ | 是 | true |
| C2-S10 | acceptance | 较高的软件测试覆盖率能否自动证明需求已经完整满足？ | 是 | true |
| C2-S11 | acceptance | 接口的权限检查应由哪一端承担？ | 是 | true |
| C2-S12 | acceptance | 数据库迁移脚本应该与什么保持版本对应？ | 是 | true |
| C2-S13 | acceptance | 低耦合要求模块之间怎样协作？ | 是 | true |
| C2-S14 | acceptance | 课程问答在模型超时后可以怎样回退？ | 是 | true |
| C2-S15 | acceptance | 评估项目风险时应同时关注哪两个方面？ | 是 | true |
| U01 | development | 量子计算的 Shor 算法如何分解大整数？ | 否 | true |
| U02 | development | 分页系统的最优页面置换算法怎样选择淘汰页面？ | 否 | false |
| U03 | development | 软件测试中的 MC/DC 覆盖率如何计算？ | 否 | false |
| U04 | development | Scrum 框架的产品负责人认证考试费用是多少？ | 否 | true |
| U05 | development | 数据库事务的两阶段提交协议如何处理协调者故障？ | 否 | false |
| U06 | acceptance | 网络中的 BBR 拥塞控制如何估算瓶颈带宽？ | 否 | true |
| U07 | acceptance | 域名系统的 DNSSEC 密钥轮换具体如何操作？ | 否 | false |
| U08 | acceptance | 数据库迁移中 PostgreSQL 的逻辑复制槽如何创建？ | 否 | true |
| U09 | acceptance | 采用 COCOMO II 模型估算软件项目人月的公式是什么？ | 否 | true |
| U10 | acceptance | 这门软件工程课程的期末考试日期是哪天？ | 否 | true |

逐题原文、来源片段编号、得分、检索词覆盖率和耗时见同目录 JSON。词项覆盖只是词面启发式；相关术语出现并不保证资料回答了问题。
