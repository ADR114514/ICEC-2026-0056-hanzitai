# 可追溯的迭代与实验布置

1. **特征和模型。** 从采集的 WAV 和加速度中提取 [时域、FFT 与频带特征](../03-vibrodiag/features.py)，用 [MATLAB CUDA](../03-vibrodiag/train_vibro_cuda.m) 拟合核岭模型，冻结参数后部署到 App。开发记录中的训练批次与正式盲测分别保存。
2. **模型移植核对。** [Android 源码与一致性参考](../03-vibrodiag/android-reference/)保留 75 条对照输入及分数。现场图可查看[全景](../03-vibrodiag/photos/fan-test-overview.jpg)与[风道遮挡装置](../03-vibrodiag/photos/fan-blocking-setup.jpg)，两张图片为同项实验装置证据，未将它们解释成不同日期的迭代照片。
3. **独立盲测复盘。** 15 条实际预测与 MATLAB 复跑逐条吻合。最终正确 2/15，14 条输出为高档正常。细节与信号统计见[盲测复核](../03-vibrodiag/盲测复核/盲测复核报告.md)；强噪声条件的下降见[抗噪报告](../03-vibrodiag/抗噪报告.md)。

此页索引已存在的代码和实测材料，未补写缺失的现场日期、运行周期或照片。
