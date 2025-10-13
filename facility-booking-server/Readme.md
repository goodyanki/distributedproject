
-----

## 全面功能展示 (共 3 个阶段)

### 准备工作

请确保你已经编译了项目 (`mvn clean package`)，并且你的服务器 JAR 文件 (`target/facility-booking-server-1.0-SNAPSHOT.jar`) 和客户端文件 (`edu.ntu.sc6103.booking.client.UDPClient`) 是可用的。

  * **终端 A：** 服务器 (Server)
  * **终端 B：** 客户端 1 (Client 1 / Actor)
  * **终端 C：** 客户端 2 (Client 2 / Watcher)

### 阶段一：基础功能与幂等性演示

此阶段旨在快速验证 `QUERY`、`BOOK`、`CHANGE` 和 `OP_A` (幂等操作) 的正确性。

#### 1\. 启动服务器 (终端 A)

启动服务器，使用最安全的 **`AT_MOST_ONCE`** 模式，且**无丢包模拟**。

```bash
# 终端 A: Server
java -jar target/facility-booking-server-1.0-SNAPSHOT.jar --port 9876 --semantic AT_MOST_ONCE
```

#### 2\. 客户端操作 (终端 B)

启动客户端并执行操作：

| 命令/操作 | 预期结果 (客户端输出) | 结果解读 |
| :--- | :--- | :--- |
| `book RoomA 0 9 0 0 11 0` | 收到 `ConfirmationId: 1` | 验证预订功能正常。 |
| `query RoomA 0` | 显示 `Day 0 (Mon)` 有一个 `[09:00 - 11:00]` 的预订。 | 验证查询功能正常。 |
| `change 1 60` | 收到 `code: OK` | 验证修改功能正常 (预订 1 变为 10:00-12:00)。 |
| `op_a RoomA` | 收到 `code: OK` | 验证幂等操作 (OpA) 正常。 |
| **查询验证** (`query RoomA 0`) | 显示预订时间已更新为 `[10:00 - 12:00]` | 验证 `CHANGE` 结果正确持久化。 |

-----

### 阶段二：容错语义对比 (核心演示)

此阶段是项目的关键，用于演示 `AT_LEAST_ONCE` 和 `AT_MOST_ONCE` 在网络故障下的行为差异。

#### 1\. 场景 A: `AT_LEAST_ONCE` 的重复执行 (故障模式)

| 步骤 | 操作 | 预期结果 (服务器日志) | 结果解读 |
| :--- | :--- | :--- | :--- |
| **重启服务器** (终端 A) | 关闭 9876，启动 9879：<br>`java -jar ... --port 9879 --semantic AT_LEAST_ONCE --replyLossRate 0.7` (调高丢包率，确保重试) | 打印 `semantic=AT_LEAST_ONCE` 和 `replyLossRate=0.700` | 确认进入高丢包、低安全性模式。 |
| **客户端重试** (终端 B) | 启动客户端，设置高重试，并发送 `op_b RoomA`：<br>`set retries 5`<br>`op_b RoomA` | 客户端：多次打印 `Timeout waiting reply... Retrying...`。**服务器：** 打印**多条** `Processing request id=XXXX op=OP_B_NON_IDEMPOTENT` 消息。 | **成功演示故障！** 服务器在 `AT_LEAST_ONCE` 模式下，将客户端重传的请求视为新请求，**重复执行了非幂等操作**。 |
| **结果验证** (终端 B) | `query RoomA 6` (OpB 默认预订 Day 6) | 客户端显示 `Query result` 中，`Day 6 intervals` 应该有 **多条** 预订记录（数量等于服务器处理次数）。 | **结论：** 证明 `AT_LEAST_ONCE` 导致了不可接受的重复执行。 |

#### 2\. 场景 B: `AT_MOST_ONCE` 的去重处理 (解决故障)

| 步骤 | 操作 | 预期结果 (服务器日志) | 结果解读 |
| :--- | :--- | :--- | :--- |
| **重启服务器** (终端 A) | 关闭 9879，启动 9880：<br>`java -jar ... --port 9880 --semantic AT_MOST_ONCE --replyLossRate 0.7` | 打印 `semantic=AT_MOST_ONCE` | 确认进入高安全性模式。 |
| **客户端重试** (终端 B) | 启动客户端，设置高重试，并发送 **`op_b RoomA`** | 客户端：多次打印 `Timeout waiting reply... Retrying...`。**服务器：** 仅第一次打印 `Processing request...`。随后的重传打印：**`Detected duplicate request (at-most-once) ... -> resend cached reply`**。 | **成功演示解决方案！** 服务器缓存了首次执行结果，阻止了重复处理，保证了操作只执行一次。 |
| **结果验证** (终端 B) | `query RoomA 6` | 客户端显示 `Day 6 intervals` 中**只有一条**新的预订记录。 | **结论：** 证明 `AT_MOST_ONCE` 成功解决了重复执行问题。 |

-----

### 阶段三：监控回调机制演示

此阶段旨在演示客户端可以注册监控，并在设施状态改变时收到异步通知。

#### 1\. 启动服务器 (终端 A)

关闭 9880，启动 9881，无丢包模拟，使用 `AT_MOST_ONCE`。

```bash
# 终端 A: Server
java -jar target/facility-booking-server-1.0-SNAPSHOT.jar --port 9881 --semantic AT_MOST_ONCE
```

#### 2\. Client 2 注册监控 (终端 C)

启动第二个客户端，注册监控并进入等待状态：

| 命令/操作 | 预期结果 (终端 C) | 结果解读 |
| :--- | :--- | :--- |
| `monitor RoomB 120` | 打印 `Now waiting for monitor callbacks...` 并**阻塞**。 | 验证客户端成功发送 `REGISTER_MONITOR`，服务器已记录终端 C 的地址和端口。 |

#### 3\. Client 1 触发更新 (终端 B)

启动第一个客户端，执行会改变 `RoomB` 状态的操作：

| 命令/操作 | 预期结果 (终端 B) | 预期结果 (终端 C) | 结果解读 |
| :--- | :--- | :--- | :--- |
| **`book RoomB 1 14 0 1 16 0`** | 收到 `ConfirmationId: X` | **立即收到**异步回调消息，内容包含新的预订信息 `[14:00 - 16:00]`。 | **成功演示回调！** 服务器在处理 `BOOK` 请求后，识别到 `RoomB` 状态改变，并向所有观察者（终端 C）发送了异步通知。 |
| `change X 30` | 收到 `code: OK` | **再次收到**异步回调消息，内容显示预订时间已更新为 `[14:30 - 16:30]`。 | 验证 `CHANGE` 操作也能正确触发回调。 |

完成以上三个阶段的演示，你就已经完整且深入地展示了你的服务器端所有功能的正确性和可靠性。