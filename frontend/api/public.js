const http = require("./api.js");
const { createError, getBaseURL } = http;

const invalid = () => {
  throw createError("PUBLIC_RESPONSE_INVALID");
};
const object = (value) =>
  value && typeof value === "object" && !Array.isArray(value)
    ? value
    : invalid();
const text = (value) => (typeof value === "string" ? value : invalid());
const id = (value) =>
  typeof value === "string" && /^[A-Za-z0-9_-]{1,128}$/.test(value)
    ? value
    : invalid();
const list = (value) => (Array.isArray(value) ? value : invalid());
const unique = (values) =>
  new Set(values.map((v) => v.id)).size === values.length ? values : invalid();
const nullableMedia = (value) => (value === null ? null : mapMedia(value));
const positive = (value) =>
  Number.isInteger(value) && value > 0 ? value : invalid();

const mapMedia = (input) => {
  const value = object(input);
  const mediaId = id(value.id);
  const base = getBaseURL().replace(/\/$/, "");
  const resolve = (url, variant) => {
    const path = `/api/v1/public/media/${mediaId}/${variant}/content`;
    if (url !== path && url !== `${base}${path}`) invalid();
    return `${base}${path}`;
  };
  if (!Number.isInteger(value.sortOrder) || value.sortOrder < 0) invalid();
  return {
    id: mediaId,
    src: resolve(value.previewUrl, "preview"),
    thumbnail: resolve(value.thumbnailUrl, "thumbnail"),
    alt: text(value.altText),
    width: positive(value.width),
    height: positive(value.height),
    sortOrder: value.sortOrder,
  };
};

const mapContact = (input) => {
  const value = object(input);
  const channels = unique(
    list(value.channels).map((entry) => {
      const channel = object(entry);
      if (
        !["wechat", "douyin", "xiaohongshu"].includes(channel.platform) ||
        !["CONFIRMED", "PLACEHOLDER", "UNCONFIGURED"].includes(
          channel.qrCodeStatus,
        )
      )
        invalid();
      return {
        id: id(channel.id),
        platform: channel.platform,
        label: text(channel.label),
        account: text(channel.account),
        qrCodeMedia: nullableMedia(channel.qrCodeMedia),
        qrCodeStatus: channel.qrCodeStatus,
      };
    }),
  );
  return {
    channels,
    phone: text(value.phone),
    address: text(value.address),
    workingHours: text(value.workingHours),
  };
};

const mapBrand = (input) => {
  const value = object(input);
  const contact = mapContact(value.contact);
  return {
    name: text(value.name),
    englishName: text(value.englishName),
    discoveryHeading: text(value.discoveryHeading),
    shareTitle: text(value.shareTitle),
    tagline: text(value.tagline),
    description: text(value.description),
    logoMedia: nullableMedia(value.logoMedia),
    contact,
    phone: contact.phone,
    address: contact.address,
    workingHours: contact.workingHours,
    team: unique(
      list(value.team).map((member) => ({
        id: id(member.id),
        name: text(member.name),
        role: text(member.role),
        bio: text(member.bio),
        specialties: list(member.specialties).map(text),
        portraitMedia: nullableMedia(member.portraitMedia),
      })),
    ),
  };
};

const mapWorkSummary = (input) => {
  const value = object(input);
  return {
    id: id(value.id),
    title: text(value.title),
    summary: text(value.summary),
    coverMedia: mapMedia(value.coverMedia),
    businessLabels: list(value.businessLabels).map(text),
    publishedAt: text(value.publishedAt),
  };
};

const mapWorkDetail = (input) => {
  const value = object(input);
  const media = unique(list(value.media).map(mapMedia));
  if (!media.length) invalid();
  return {
    id: id(value.id),
    title: text(value.title),
    summary: text(value.summary),
    description: text(value.description),
    media,
    coverMedia: media[0],
    businessLabels: list(value.businessLabels).map(text),
    packageLabel: text(value.packageLabel),
    publishedAt: text(value.publishedAt),
    contact: mapContact(value.contact),
  };
};

const mapPackageSummary = (input) => {
  const value = object(input);
  if (!["INQUIRY", "FROM", "FIXED", "UNDISCLOSED"].includes(value.priceType))
    invalid();
  return {
    id: id(value.id),
    businessId: id(value.businessId),
    businessName: text(value.businessName),
    name: text(value.name),
    summary: text(value.summary),
    displayPrice: text(value.displayPrice),
    priceType: value.priceType,
    coverMedia: nullableMedia(value.coverMedia),
  };
};

const mapPackageDetail = (input) => {
  const value = object(input);
  return {
    ...mapPackageSummary(value),
    serviceItems: list(value.serviceItems).map(text),
    suitableFor: text(value.suitableFor),
    durationMinutes:
      value.durationMinutes === null ? null : positive(value.durationMinutes),
    tips: list(value.tips).map(text),
    notices: list(value.notices).map(text),
    publishedAt: text(value.publishedAt),
    contact: mapContact(value.contact),
  };
};

const mapBusinessSummary = (input) => {
  const value = object(input);
  if (
    typeof value.hasPublishedPackages !== "boolean" ||
    !Number.isInteger(value.packageCount) ||
    value.packageCount < 0 ||
    value.hasPublishedPackages !== value.packageCount > 0
  )
    invalid();
  return {
    id: id(value.id),
    name: text(value.name),
    summary: text(value.summary),
    coverMedia: nullableMedia(value.coverMedia),
    hasPublishedPackages: value.hasPublishedPackages,
    packageCount: value.packageCount,
  };
};

const mapBusinessDetail = (input) => ({
  ...mapBusinessSummary(input),
  packages: unique(list(input.packages).map(mapPackageSummary)),
});

const mapHome = (input) => {
  const value = object(input);
  return {
    brand: mapBrand(value.brand),
    contact: mapContact(value.contact),
    heroItems: unique(
      list(value.heroItems).map((entry) => ({
        id: id(entry.id),
        title: text(entry.title),
        subtitle: text(entry.subtitle),
        action: text(entry.action),
        media: mapMedia(entry.media),
        target: text(entry.target),
        targetId: id(entry.targetId),
      })),
    ),
    featuredWorks: unique(list(value.featuredWorks).map(mapWorkSummary)),
    businesses: unique(list(value.businesses).map(mapBusinessSummary)),
    packages: unique(list(value.packages).map(mapPackageSummary)),
  };
};

const detail = async (path, mapper) => {
  const response = await http.get(path);
  return {
    ...mapper(object(response.data)),
    meta: response.meta,
    requestId: response.requestId,
  };
};
const page = async (path, params, mapper) => {
  const response = await http.get(path, params);
  const payload = object(response.data);
  if (
    payload.nextCursor !== response.meta.nextCursor ||
    (payload.nextCursor !== null && typeof payload.nextCursor !== "string")
  )
    invalid();
  return {
    items: unique(list(payload.items).map(mapper)),
    nextCursor: payload.nextCursor,
    contentVersion: text(response.meta.contentVersion),
    dataMode: response.meta.dataMode,
    requestId: text(response.requestId),
  };
};

const getHome = () => detail("/api/v1/public/home", mapHome);
const getBrand = () => detail("/api/v1/public/brand", mapBrand);
const getWorks = (params = {}) =>
  page("/api/v1/public/works", params, mapWorkSummary);
const getWork = (workId) =>
  detail(
    `/api/v1/public/works/${encodeURIComponent(id(workId))}`,
    mapWorkDetail,
  );
const getBusinesses = () =>
  page("/api/v1/public/businesses", {}, mapBusinessSummary);
const getBusiness = (businessId) =>
  detail(
    `/api/v1/public/businesses/${encodeURIComponent(id(businessId))}`,
    mapBusinessDetail,
  );
const getPackages = (params = {}) =>
  page("/api/v1/public/packages", params, mapPackageSummary);
const getPackage = (packageId) =>
  detail(
    `/api/v1/public/packages/${encodeURIComponent(id(packageId))}`,
    mapPackageDetail,
  );
const getMedia = (mediaId, variant = "preview") =>
  detail(
    `/api/v1/public/media/${encodeURIComponent(id(mediaId))}/${variant}`,
    mapMedia,
  );

module.exports = {
  mapMedia,
  mapContact,
  mapBrand,
  mapWorkSummary,
  mapWorkDetail,
  mapPackageSummary,
  mapPackageDetail,
  mapBusinessSummary,
  mapBusinessDetail,
  mapHome,
  getHome,
  getBrand,
  getWorks,
  getWork,
  getBusinesses,
  getBusiness,
  getPackages,
  getPackage,
  getMedia,
};
