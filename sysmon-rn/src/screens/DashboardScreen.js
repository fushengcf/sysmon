import React, { useRef } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  ScrollView,
  StyleSheet,
  Dimensions,
  PanResponder,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useWebSocket, WsStatus } from '../context/WebSocketContext';
import { Colors } from '../utils/theme';
import { formatSpeedValue, formatSpeedUnit, formatMb } from '../utils/format';
import GaugeChart from '../components/GaugeChart';
import { DualLineChart, LineChart } from '../components/LineChart';

const { width: SCREEN_WIDTH } = Dimensions.get('window');

export default function DashboardScreen() {
  const { state, actions } = useWebSocket();
  const { metrics, cpuHistory, memHistory, netRxHistory, netTxHistory, connectedUrl, savedUrls, savedRemarks } = state;

  const cpuValue = metrics?.cpu_usage_percent ?? 0;
  const memValue = metrics?.memory_usage_percent ?? 0;
  const memUsedMb = metrics?.memory_used_mb ?? 0;
  const memTotalMb = metrics?.memory_total_mb ?? 0;
  const rxKbps = metrics?.net_rx_kbps ?? 0;
  const txKbps = metrics?.net_tx_kbps ?? 0;

  const connectedIdx = savedUrls.indexOf(connectedUrl);
  const connectedRemark = connectedIdx >= 0 ? (savedRemarks[connectedIdx] || '') : '';

  // 左右滑动切换 URL
  const dragAccum = useRef(0);
  const panResponder = useRef(
    PanResponder.create({
      onMoveShouldSetPanResponder: (_, g) => Math.abs(g.dx) > 10,
      onPanResponderGrant: () => { dragAccum.current = 0; },
      onPanResponderMove: (_, g) => {
        dragAccum.current = g.dx;
        if (dragAccum.current > 80) {
          dragAccum.current = 0;
          actions.switchToPrevUrl();
        } else if (dragAccum.current < -80) {
          dragAccum.current = 0;
          actions.switchToNextUrl();
        }
      },
      onPanResponderRelease: () => { dragAccum.current = 0; },
    })
  ).current;

  return (
    <SafeAreaView style={styles.safeArea}>
      <View style={styles.container} {...panResponder.panHandlers}>
        {/* ── 顶部 Header ── */}
        <View style={styles.topBar}>
          <Text style={styles.topTitle}>SYSMON</Text>
          <View style={styles.topRight}>
            {connectedRemark ? (
              <View style={styles.liveBadge}>
                <View style={styles.liveDot} />
                <Text style={styles.liveText} numberOfLines={1}>{connectedRemark}-LIVE</Text>
              </View>
            ) : (
              <View style={styles.liveBadge}>
                <View style={styles.liveDot} />
                <Text style={styles.liveText}>LIVE</Text>
              </View>
            )}
            {savedUrls.length > 1 && (
              <Text style={styles.swipeHint}>◀ 滑动切换 ▶</Text>
            )}
            <TouchableOpacity style={styles.discBtn} onPress={actions.disconnect}>
              <Text style={styles.discText}>✕ DISC</Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* ── 主内容区（横向布局） ── */}
        <View style={styles.mainRow}>
          {/* 左列：网速图 */}
          <View style={[styles.col, styles.networkCol]}>
            <NetworkCard
              rxKbps={rxKbps}
              txKbps={txKbps}
              rxHistory={netRxHistory}
              txHistory={netTxHistory}
            />
          </View>

          {/* 右列：CPU + MEM */}
          <View style={[styles.col, styles.metricsCol]}>
            {/* CPU 卡片 */}
            <View style={[styles.metricCard, styles.cpuCard]}>
              <GaugeCardContent
                label="CPU"
                value={cpuValue}
                history={cpuHistory}
                mainColor={Colors.CpuGreen}
                gradientEnd={Colors.CpuCyan}
              />
            </View>

            {/* MEM 卡片 */}
            <View style={[styles.metricCard, styles.memCard]}>
              <GaugeCardContent
                label="MEM"
                value={memValue}
                history={memHistory}
                mainColor={Colors.MemPurple}
                gradientEnd={Colors.MemPink}
                subText={memTotalMb > 0 ? `${formatMb(memUsedMb)} / ${formatMb(memTotalMb)}` : null}
              />
            </View>
          </View>
        </View>

        {/* ── CPU 多核（有多核时展示） ── */}
        {(metrics?.cpu_per_core?.length ?? 0) > 1 && (
          <CoresRow cores={metrics.cpu_per_core} />
        )}
      </View>
    </SafeAreaView>
  );
}

// ── 网速卡片 ─────────────────────────────────────────────────────────────────────

function NetworkCard({ rxKbps, txKbps, rxHistory, txHistory }) {
  const cardWidth = SCREEN_WIDTH * 0.55 - 24;

  return (
    <View style={[styles.glassCard, styles.networkGlassCard]}>
      <View style={styles.netLabelRow}>
        <Text style={[styles.cardLabel, { color: Colors.NetAmber }]}>NETWORK</Text>
        <View style={styles.netLegend}>
          <Text style={[styles.legendItem, { color: Colors.NetAmber }]}>↓ RX</Text>
          <Text style={[styles.legendItem, { color: Colors.NetPink }]}>↑ TX</Text>
        </View>
      </View>

      <View style={{ flex: 1, justifyContent: 'center' }}>
        <DualLineChart
          rxData={rxHistory}
          txData={txHistory}
          width={cardWidth - 16}
          height={80}
        />
      </View>

      <View style={styles.speedRow}>
        <SpeedItem label="↓" value={formatSpeedValue(rxKbps)} unit={formatSpeedUnit(rxKbps)} color={Colors.NetAmber} />
        <SpeedItem label="↑" value={formatSpeedValue(txKbps)} unit={formatSpeedUnit(txKbps)} color={Colors.NetPink} />
      </View>
    </View>
  );
}

// ── 仪表盘卡片内容 ───────────────────────────────────────────────────────────────

function GaugeCardContent({ label, value, history, mainColor, gradientEnd, subText }) {
  const size = 100;
  return (
    <View style={styles.gaugeCardInner}>
      <View style={styles.gaugeVertLabel}>
        {label.split('').map((c, i) => (
          <Text key={i} style={[styles.gaugeVertChar, { color: mainColor }]}>{c}</Text>
        ))}
      </View>
      <View style={styles.gaugeContent}>
        <View style={{ position: 'relative', width: size, height: size, justifyContent: 'center', alignItems: 'center' }}>
          <GaugeChart value={value} color={mainColor} gradientEndColor={gradientEnd} size={size} />
          <View style={styles.gaugeCenter}>
            <Text style={[styles.gaugeValue, { color: mainColor }]}>{Math.round(value)}</Text>
            <Text style={styles.gaugeUnit}>%</Text>
          </View>
        </View>
        {subText && (
          <Text style={styles.gaugeSubText}>{subText}</Text>
        )}
      </View>
    </View>
  );
}

// ── 多核行 ────────────────────────────────────────────────────────────────────────

function CoresRow({ cores }) {
  return (
    <View style={styles.coresRow}>
      <Text style={[styles.cardLabel, { color: Colors.CoreCyan, marginBottom: 6 }]}>
        CORES  {cores.length}c
      </Text>
      <View style={styles.coresList}>
        {cores.map((v, i) => (
          <View key={i} style={styles.coreItem}>
            <Text style={styles.coreName}>C{i}</Text>
            <View style={styles.coreBar}>
              <View style={[styles.coreBarFill, {
                width: `${Math.max(v, 2)}%`,
                backgroundColor: v > 50 ? Colors.CoreAmber : v > 20 ? Colors.CoreBlue : Colors.CoreCyan,
              }]} />
            </View>
            <Text style={[styles.coreValue, {
              color: v > 50 ? Colors.CoreAmber : v > 20 ? Colors.CoreBlue : Colors.TextSecondary
            }]}>
              {Math.round(v)}%
            </Text>
          </View>
        ))}
      </View>
    </View>
  );
}

// ── 网速数值行 ────────────────────────────────────────────────────────────────────

function SpeedItem({ label, value, unit, color }) {
  return (
    <View style={styles.speedItem}>
      <View style={[styles.speedDot, { backgroundColor: color }]} />
      <Text style={styles.speedLabel}>{label}</Text>
      <Text style={[styles.speedValue, { color: Colors.TextPrimary }]}>{value}</Text>
      <Text style={styles.speedUnit}>{unit}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: Colors.BgDeep,
  },
  container: {
    flex: 1,
    padding: 10,
    gap: 8,
  },
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 4,
  },
  topTitle: {
    color: Colors.TextPrimary,
    fontSize: 16,
    fontWeight: 'bold',
    fontFamily: 'monospace',
    letterSpacing: 4,
  },
  topRight: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  liveBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: Colors.LiveGreenBg,
    borderRadius: 20,
    paddingHorizontal: 8,
    paddingVertical: 3,
    gap: 4,
    maxWidth: 120,
  },
  liveDot: {
    width: 5,
    height: 5,
    borderRadius: 2.5,
    backgroundColor: Colors.LiveGreen,
  },
  liveText: {
    color: Colors.LiveGreen,
    fontSize: 9,
    fontFamily: 'monospace',
    fontWeight: '600',
  },
  swipeHint: {
    color: Colors.TextMuted + '80',
    fontSize: 8,
    fontFamily: 'monospace',
  },
  discBtn: {
    backgroundColor: '#1A1F2E',
    borderWidth: 1,
    borderColor: Colors.DangerRed + '55',
    borderRadius: 6,
    paddingHorizontal: 8,
    paddingVertical: 3,
  },
  discText: {
    color: Colors.DangerRed + 'CC',
    fontSize: 9,
    fontFamily: 'monospace',
    fontWeight: '500',
  },
  mainRow: {
    flex: 1,
    flexDirection: 'row',
    gap: 8,
  },
  col: {
    flex: 1,
  },
  networkCol: {
    flex: 5.5,
  },
  metricsCol: {
    flex: 3.5,
    gap: 8,
  },
  glassCard: {
    flex: 1,
    backgroundColor: Colors.BgCard,
    borderRadius: 16,
    borderWidth: 1,
    padding: 10,
  },
  networkGlassCard: {
    borderColor: Colors.NetAmber + '44',
  },
  metricCard: {
    flex: 1,
    backgroundColor: Colors.BgCard,
    borderRadius: 16,
    borderWidth: 1,
    padding: 8,
  },
  cpuCard: {
    borderColor: Colors.CpuGreen + '44',
  },
  memCard: {
    borderColor: Colors.MemPurple + '44',
  },
  netLabelRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 4,
  },
  cardLabel: {
    fontSize: 10,
    fontFamily: 'monospace',
    fontWeight: 'bold',
    letterSpacing: 2,
  },
  netLegend: {
    flexDirection: 'row',
    gap: 8,
  },
  legendItem: {
    fontSize: 9,
    fontFamily: 'monospace',
  },
  speedRow: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 6,
  },
  speedItem: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  speedDot: {
    width: 7,
    height: 7,
    borderRadius: 3.5,
  },
  speedLabel: {
    color: Colors.TextSecondary,
    fontSize: 11,
    fontFamily: 'monospace',
  },
  speedValue: {
    fontSize: 18,
    fontWeight: 'bold',
    fontFamily: 'monospace',
  },
  speedUnit: {
    color: Colors.TextSecondary,
    fontSize: 10,
    fontFamily: 'monospace',
  },
  gaugeCardInner: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
  },
  gaugeVertLabel: {
    width: 16,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 1,
  },
  gaugeVertChar: {
    fontSize: 10,
    fontWeight: 'bold',
    fontFamily: 'monospace',
    lineHeight: 13,
  },
  gaugeContent: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  gaugeCenter: {
    position: 'absolute',
    alignItems: 'center',
    justifyContent: 'center',
  },
  gaugeValue: {
    fontSize: 24,
    fontWeight: 'bold',
    fontFamily: 'monospace',
    lineHeight: 28,
  },
  gaugeUnit: {
    color: Colors.TextSecondary,
    fontSize: 11,
  },
  gaugeSubText: {
    color: Colors.TextSecondary,
    fontSize: 9,
    fontFamily: 'monospace',
    marginTop: 2,
    textAlign: 'center',
  },
  coresRow: {
    backgroundColor: Colors.BgCard,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: Colors.CoreCyan + '44',
    padding: 10,
  },
  coresList: {
    gap: 4,
  },
  coreItem: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
  },
  coreName: {
    color: Colors.TextSecondary,
    fontSize: 9,
    fontFamily: 'monospace',
    width: 16,
  },
  coreBar: {
    flex: 1,
    height: 10,
    backgroundColor: Colors.BgSlate,
    borderRadius: 5,
    overflow: 'hidden',
  },
  coreBarFill: {
    height: '100%',
    borderRadius: 5,
    minWidth: 2,
  },
  coreValue: {
    fontSize: 9,
    fontFamily: 'monospace',
    width: 28,
    textAlign: 'right',
  },
});
