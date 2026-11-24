# 第二阶段 · 开放式项目挑战

- [项目三 · 旋转机械声振诊断](03-vibrodiag/)：训练和正式盲测数据、时频域特征代码、MATLAB 模型、复核与抗噪报告。
- [WAV、CSV 原始数据与会话日志](process_logs/03-vibrodiag/) · [迭代记录与实物照索引](iterations/)。
- [Android App 源码](app-source/)：包含提交所用的 PowerTrace 1.5.1、ChargeAudit 1.0.3、VibroDiag 1.5.0 及共享组件。使用 JDK 17 + Android SDK 36，在源码目录执行 ./gradlew assembleDebug。

正式盲测采用冻结 MATLAB 核岭模型。录音使用 Git LFS 保存，数据下载与复现步骤见[项目三说明](03-vibrodiag/)。
