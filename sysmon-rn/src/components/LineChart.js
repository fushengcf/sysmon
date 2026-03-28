import React from 'react';
import { View } from 'react-native';
import Svg, { Path, Defs, LinearGradient, Stop, G } from 'react-native-svg';

/**
 * 单折线面积图
 */
export function LineChart({ data = [], color, width = 200, height = 80 }) {
  if (data.length < 2) {
    return <View style={{ width, height }} />;
  }

  const max = Math.max(...data, 1);
  const points = data.map((v, i) => ({
    x: (i / (data.length - 1)) * width,
    y: height - (v / max) * height * 0.85 - height * 0.05,
  }));

  // 折线路径
  const linePath = points.reduce((acc, p, i) => {
    if (i === 0) return `M ${p.x} ${p.y}`;
    const prev = points[i - 1];
    const cpx = (prev.x + p.x) / 2;
    return `${acc} C ${cpx} ${prev.y} ${cpx} ${p.y} ${p.x} ${p.y}`;
  }, '');

  // 填充区域路径
  const fillPath = `${linePath} L ${width} ${height} L 0 ${height} Z`;

  return (
    <View style={{ width, height }}>
      <Svg width={width} height={height}>
        <Defs>
          <LinearGradient id={`lineGrad_${color}`} x1="0" y1="0" x2="0" y2="1">
            <Stop offset="0%" stopColor={color} stopOpacity="0.4" />
            <Stop offset="100%" stopColor={color} stopOpacity="0.02" />
          </LinearGradient>
        </Defs>
        {/* 填充区域 */}
        <Path d={fillPath} fill={`url(#lineGrad_${color})`} />
        {/* 折线 */}
        <Path d={linePath} fill="none" stroke={color} strokeWidth={1.5} strokeLinecap="round" />
      </Svg>
    </View>
  );
}

/**
 * 双折线面积图（网速：RX + TX）
 */
export function DualLineChart({ rxData = [], txData = [], width = 200, height = 80 }) {
  const rxColor = '#FFB300';
  const txColor = '#FF4081';

  const allData = [...rxData, ...txData];
  if (allData.length === 0) {
    return <View style={{ width, height }} />;
  }

  const max = Math.max(...allData, 1);
  const len = Math.max(rxData.length, txData.length);

  const buildPath = (data) => {
    if (data.length < 2) return null;
    const points = data.map((v, i) => ({
      x: (i / (len - 1)) * width,
      y: height - (v / max) * height * 0.85 - height * 0.05,
    }));
    return points.reduce((acc, p, i) => {
      if (i === 0) return `M ${p.x} ${p.y}`;
      const prev = points[i - 1];
      const cpx = (prev.x + p.x) / 2;
      return `${acc} C ${cpx} ${prev.y} ${cpx} ${p.y} ${p.x} ${p.y}`;
    }, '');
  };

  const rxLinePath = buildPath(rxData);
  const txLinePath = buildPath(txData);

  const rxFillPath = rxLinePath ? `${rxLinePath} L ${width} ${height} L 0 ${height} Z` : null;
  const txFillPath = txLinePath ? `${txLinePath} L ${width} ${height} L 0 ${height} Z` : null;

  return (
    <View style={{ width, height }}>
      <Svg width={width} height={height}>
        <Defs>
          <LinearGradient id="rxGrad" x1="0" y1="0" x2="0" y2="1">
            <Stop offset="0%" stopColor={rxColor} stopOpacity="0.35" />
            <Stop offset="100%" stopColor={rxColor} stopOpacity="0.02" />
          </LinearGradient>
          <LinearGradient id="txGrad" x1="0" y1="0" x2="0" y2="1">
            <Stop offset="0%" stopColor={txColor} stopOpacity="0.35" />
            <Stop offset="100%" stopColor={txColor} stopOpacity="0.02" />
          </LinearGradient>
        </Defs>
        {rxFillPath && <Path d={rxFillPath} fill="url(#rxGrad)" />}
        {txFillPath && <Path d={txFillPath} fill="url(#txGrad)" />}
        {rxLinePath && (
          <Path d={rxLinePath} fill="none" stroke={rxColor} strokeWidth={1.5} strokeLinecap="round" />
        )}
        {txLinePath && (
          <Path d={txLinePath} fill="none" stroke={txColor} strokeWidth={1.5} strokeLinecap="round" />
        )}
      </Svg>
    </View>
  );
}
