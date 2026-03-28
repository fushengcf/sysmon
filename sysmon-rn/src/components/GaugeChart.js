import React from 'react';
import { View } from 'react-native';
import Svg, { Circle, Path, Defs, LinearGradient, Stop } from 'react-native-svg';

/**
 * 半圆仪表盘组件
 * @param {number} value - 0~100 的值
 * @param {string} color - 主色
 * @param {string} gradientEndColor - 渐变终点色
 */
export default function GaugeChart({ value, color, gradientEndColor, size = 120 }) {
  const cx = size / 2;
  const cy = size / 2;
  const r = size * 0.42;
  const strokeWidth = size * 0.065;
  const trackWidth = size * 0.04;

  // 从 -215° 到 35°，共 250° 的圆弧
  const startAngle = -215;
  const endAngle = 35;
  const totalAngle = endAngle - startAngle; // 250°

  const toRad = (deg) => (deg * Math.PI) / 180;

  // 计算圆弧路径
  const describeArc = (cx, cy, r, startDeg, endDeg) => {
    const start = {
      x: cx + r * Math.cos(toRad(startDeg)),
      y: cy + r * Math.sin(toRad(startDeg)),
    };
    const end = {
      x: cx + r * Math.cos(toRad(endDeg)),
      y: cy + r * Math.sin(toRad(endDeg)),
    };
    const largeArc = endDeg - startDeg > 180 ? 1 : 0;
    return `M ${start.x} ${start.y} A ${r} ${r} 0 ${largeArc} 1 ${end.x} ${end.y}`;
  };

  const clampedValue = Math.min(100, Math.max(0, value));
  const progressAngle = startAngle + (totalAngle * clampedValue) / 100;

  const trackPath = describeArc(cx, cy, r, startAngle, endAngle);
  const progressPath = clampedValue > 0
    ? describeArc(cx, cy, r, startAngle, progressAngle)
    : null;

  return (
    <View style={{ width: size, height: size }}>
      <Svg width={size} height={size}>
        <Defs>
          <LinearGradient id={`gaugeGrad_${color}`} x1="0%" y1="0%" x2="100%" y2="0%">
            <Stop offset="0%" stopColor={color} stopOpacity="1" />
            <Stop offset="100%" stopColor={gradientEndColor || color} stopOpacity="1" />
          </LinearGradient>
        </Defs>

        {/* 轨道 */}
        <Path
          d={trackPath}
          fill="none"
          stroke="rgba(255,255,255,0.06)"
          strokeWidth={trackWidth}
          strokeLinecap="round"
        />

        {/* 进度弧 */}
        {progressPath && (
          <Path
            d={progressPath}
            fill="none"
            stroke={`url(#gaugeGrad_${color})`}
            strokeWidth={strokeWidth}
            strokeLinecap="round"
          />
        )}
      </Svg>
    </View>
  );
}
