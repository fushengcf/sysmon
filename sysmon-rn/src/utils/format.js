// 格式化工具函数

/**
 * 格式化网速数值
 * @param {number} kbps
 * @returns {string}
 */
export function formatSpeedValue(kbps) {
  if (kbps >= 1024) {
    return (kbps / 1024).toFixed(1);
  }
  return kbps.toFixed(1);
}

/**
 * 格式化网速单位
 * @param {number} kbps
 * @returns {string}
 */
export function formatSpeedUnit(kbps) {
  return kbps >= 1024 ? 'MB/s' : 'KB/s';
}

/**
 * 格式化内存大小（MB -> G/M）
 * @param {number} mb
 * @returns {string}
 */
export function formatMb(mb) {
  if (mb >= 1024) {
    return `${(mb / 1024).toFixed(1)}G`;
  }
  return `${mb}M`;
}

/**
 * 格式化百分比
 * @param {number} value
 * @returns {string}
 */
export function formatPercent(value) {
  return `${Math.round(value)}%`;
}
