const yuanLabel = (value, fallback) => {
  if (typeof value !== "string") return fallback;
  const text = value.trim();
  if (!text) return fallback;
  if (text.startsWith("¥") || text.startsWith("￥") || !/^\d/.test(text))
    return text;
  return `¥${text}`;
};

const priceLine = (lines, key) =>
  lines && typeof lines[key] === "string" ? lines[key] : "";
const priceLabel = (pkg = {}, lines = {}) => {
  if (pkg.priceType === "INQUIRY") return priceLine(lines, "priceInquiry");
  if (pkg.priceType === "FROM")
    return yuanLabel(pkg.displayPrice, priceLine(lines, "priceFromMissing"));
  if (pkg.priceType === "FIXED")
    return yuanLabel(pkg.displayPrice, priceLine(lines, "priceFixedMissing"));
  return priceLine(lines, "priceUnpublished");
};

const groupServices = (businesses = [], packages = []) =>
  businesses.map((business) => ({
    ...business,
    packages: packages.filter((pkg) => pkg.businessId === business.id),
  }));

const galleryMedia = (work = {}) => {
  return Array.isArray(work.media) ? work.media : [];
};

const readLayout = (key, allowed, fallback) => {
  try {
    const value = wx.getStorageSync(key);
    return allowed.includes(value) ? value : fallback;
  } catch {
    return fallback;
  }
};
const rememberLayout = (key, value) => {
  try {
    wx.setStorageSync(key, value);
  } catch {
    /* Browsing still works if local storage is unavailable. */
  }
};

const nextLayout = (current, order) =>
  order.length ? order[(order.indexOf(current) + 1) % order.length] : current;

module.exports = {
  priceLabel,
  groupServices,
  galleryMedia,
  readLayout,
  rememberLayout,
  nextLayout,
};
