<div align="center">
  <img src="artwork/sesame-ag-app-icon.svg" width="140" alt="Sesame-AG Logo" />
  <h1>Sesame-AG</h1>
  <p><strong>面向学习研究场景的独立演进 Android LSPosed 模块。</strong></p>

  <p>
    <a href="LICENSE"><img src="https://img.shields.io/github/license/Sesame-AG/Sesame-AG?style=flat-square&color=orange" alt="License" /></a>
    <a href="https://github.com/Sesame-AG/Sesame-AG/discussions"><img src="https://img.shields.io/github/discussions/Sesame-AG/Sesame-AG?style=flat-square&color=success" alt="Discussions" /></a>
  </p>

  <p><code>Sesame-AG</code> 一个面向学习研究场景的 Alipay LSPosed 模块分支。</p>
</div>

---

## 🚀 项目简介

虽然项目在演进过程中吸收了多条公开实现思路的精华，但它**不是**任何单一上游仓库的简单延续，而是成长为一个**完全独立演进**的研究分支。与任何上游仓库维护者无任何关联，我们倡导开源与社区共创。

> [!IMPORTANT]
> 为避免滥用，确保模块以学习研究为主的定位，当前仓库仅对以下环境组合提供“支持维护”：
>
> - `LSPosed（libxposed API 102+；模块框架 min/target API 均为 102）`
> - `已 Root`
> - `Android 16+`
> - `目标应用版本 >=v10.3.96.8100(作者使用 v10.8.20.8000)`
>
> 上述范围之外的框架版本、系统版本、运行条件或仅能在非上述环境中复现的问题，可能仍会被讨论，但不属于维护者承诺持续适配、优先处理或长期回归验证的范围。

请先确认你已经接受本仓库 [LEGAL.md](LEGAL.md) 中整理的许可说明、使用边界、风险提示与免责声明。

当你下载、克隆、编译、安装、运行、修改、分发或以其他方式使用本项目时，即视为你已经阅读、理解并接受 [LICENSE](LICENSE) 与本仓库 [LEGAL.md](LEGAL.md) 中的相关说明。

---

## 🤝 社区与贡献

我们鼓励每一位研究者参与到 `Sesame-AG` 的建设中来，共同维护一个健康、积极的开源环境。

- **分享 RPC 配置**：如果你发现了有趣的 RPC 调用或实用的调试配置，欢迎前往 [RPC 分享讨论区](https://github.com/Sesame-AG/Sesame-AG/discussions/categories/rpc-%E5%88%86%E4%BA%AB) 进行分享。
- **讨论想法**：有好的想法？请优先通过 [Discussions](https://github.com/Sesame-AG/Sesame-AG/discussions) 进行交流，确保 Issue 列表聚焦于可复现的技术问题。
- **参与开发**：请参考 [CONTRIBUTING.md](CONTRIBUTING.md) 了解协作规范。

---

## ⚖️ 法律与协议

在使用本项目之前，请务必阅读并理解以下文档：

- [LEGAL.md](LEGAL.md)：包含许可说明、使用边界、风险提示与免责声明。
- [LICENSE](LICENSE)：项目采用 **AGPL-3.0** 协议开源。

<details>
<summary><b>点击查看：研究参考与技术源流</b></summary>

本项目在独立演进过程中，曾参考或吸收了以下公开维护线的实现思路（按时间/关联度排序）：

早期起点 ([yongjun925/autocollectenergy](https://github.com/yongjun925/autocollectenergy)) -> `XQuickEnergy` 系列 ([pansong291](https://github.com/pansong291/XQuickEnergy) / [constanline](https://github.com/constanline/XQuickEnergy)) -> `Sesame` 系列 ([LazyImmortal](https://github.com/LazyImmortal/Sesame) / [TKaxv-7S](https://github.com/TKaxv-7S/Sesame-TK) / [Liujishou](https://github.com/Liujishou/Sesame-TK-Fork) / [Fansirsqi](https://github.com/Fansirsqi/Sesame-TK))。

再次强调：`Sesame-AG` 现已进入独立演进阶段，历史源流仅供技术参考，不代表单一的法律继承关系。

</details>

---

## 开源协议

### 协议说明

> [!NOTE]
> 由于历史代码跨越多个维护阶段，协议更适合按时间切分理解：

| 时间范围 / 版本节点 | 适用说明 |
| --- | --- |
| **2024 年 7 月 15 日之前** 的历史代码 | 按上游公开说明，主要落在 `Apache-2.0` 语境。 |
| **2024 年 7 月 15 日** 起的后续代码 | 仓库历史说明曾表述为 `GPLv3`，并附带“禁止商业用途、禁止二次修改后闭源发布”的项目声明。 |
| **2025 年 7 月 23 日** 起至 **2025 年 12 月 15 日** 之间的本仓库历史说明 | 仓库声明曾切换到 [WTFPL](https://www.wtfpl.net/)。 |
| 2025 年 12 月 15 日-`3871-0.0.3` 版本 | 项目开源协议为 `GPLv3`，并继续附带相同的项目声明。 |
| `3871-0.0.3` 版本起 | 项目开源协议调整为 `AGPL-3.0`。 |

### 相关文档

- [LEGAL.md](LEGAL.md)：适用说明、非代码资产边界、风险提示与免责声明。
- [CONTRIBUTING.md](CONTRIBUTING.md)：Issue / PR 规则与协作边界。

---

## 贡献者

感谢所有为 `Sesame-AG` 提交代码、修复问题、补全文档与改进协作流程的贡献者。

[![贡献者头像墙](https://contrib.rocks/image?repo=Sesame-AG/Sesame-AG&max=200)](https://github.com/Sesame-AG/Sesame-AG/graphs/contributors)

