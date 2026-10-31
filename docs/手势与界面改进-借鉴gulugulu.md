# 手势与界面改进（借鉴 gulugulu，2026-11）

参考对象：`aaronzzx/gulugulu` v1.6.1（Compose/Material3，无源码，按 Boss 授权反编译**仅用于学习交互与设计思路**，未复制代码/资源/文案）。

## 1. 借鉴到的手势模型

| 能力 | gulugulu | 本次 OneHandX | 说明 |
|---|---|---|---|
| 方向 | 右 / 右上 / 右下 / 上 / 下（5 向） | 新增 **垂直上 / 垂直下**（VU / VD） | 纯上下滑动此前会被判为斜向并被水平阈值挡掉 |
| 轻触 | 单击 / 双击 | 新增 **单击 / 双击**（`-C` / `-W` 槽位） | 350ms 内二次轻触判双击；沿用长按 500ms 判定 |
| 长度 | 短距离 / 长距离两套映射 | 已有 短滑 / 长滑 / 滑动保持 | 保持一致 |
| 触发距离 | 短 / 长可调（含「立即触发」） | 已有 触发距离 + 立即执行 | 保持一致 |

识别细节：`GestureRules.direction()` 在水平角阈值之前增加垂直判定（`|inward| < |dy| * 0.32` → VU/VD），`commit()` 的距离阈值改用 `max(inward, |dy|)`，因此垂直手势不再被「必须有水平位移」拦下。

## 2. 借鉴到的界面结构

- **首页「初始设置」清单**（新增）：无障碍服务 / 忽略电池优化 / 自启动与后台运行，三项都带状态与跳转，和 gulugulu 的「初始设置」一致，解决 ColorOS 上服务被清理、用户不知道去哪授权的问题。
- 编辑页手势列表保持「图标 + 名称 + 当前动作 + chevron」的双行结构，并加入 5 个方向与单击/双击行。
- 未采用的：Material3 动态取色与紫色强调（与项目既定「克制单色 + 苹果式排版」冲突）；动作面板扇形/文件夹与 Shizuku 动作（列为后续）。

## 3. 验证（模拟器 OneHandTest · Android 16）

| 用例 | 结果 |
|---|---|
| 水平直滑 | `gesture id=L1 len=L dir=H` → `L1-S-H back ok=true` |
| 垂直上滑 | `dir=VU` → `L1-S-VU notifications ok=true` |
| 垂直下滑 | `dir=VD`（识别正确；未绑定动作） |
| 单击 | `click slot=L1-C action=recents ok=true` |
| 双击 | `double slot=L1-W action=home ok=true` |
| 三连击 | 先判双击，第三次再判单击（可接受语义） |
| 仪器回归 | Smoke 57 checks PASS；ProductFlow 49 checks PASS |
| 界面 | 首页初始设置三项、编辑页 5 方向 + 单击/双击行均正常渲染 |
