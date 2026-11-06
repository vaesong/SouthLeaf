const yuanLabel = (value, fallback) => {
  if (typeof value !== "string") return fallback;
  const text = value.trim();
  if (!text) return fallback;
  if (text.startsWith("¥") || text.startsWith("￥") || !/^\d/.test(text))
    return text;
  return `¥${text}`;
};

const priceLabel = (pkg = {}) => {
  if (pkg.priceType === "INQUIRY") return "价格请询";
  if (pkg.priceType === "FROM")
    return yuanLabel(pkg.displayPrice, "起价以确认资料为准");
  if (pkg.priceType === "FIXED")
    return yuanLabel(pkg.displayPrice, "价格以确认资料为准");
  return "价格待公布";
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
