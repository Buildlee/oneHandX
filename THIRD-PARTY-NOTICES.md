# 第三方组件声明

本项目为原生 Java 实现，运行时依赖如下：

| 组件 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| androidx.appcompat:appcompat | 1.7.0 | Apache-2.0 | 基础 Activity / 兼容层 |
| androidx.core / androidx.fragment 等（appcompat 传递依赖） | 随上表 | Apache-2.0 | 兼容支持 |
| org.jetbrains.kotlin:kotlin-stdlib | 1.8.22（传递依赖，锁定版本） | Apache-2.0 | 兼容依赖 |
| dev.rikka.shizuku:api / provider / aidl / shared | 13.1.5 | MIT | 可选的 Shizuku 特权桥接 |

本文件不包含任何三星电子（Samsung Electronics）的代码、素材或二进制。

## Shizuku API MIT License

Copyright (c) 2021 RikkaW

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
