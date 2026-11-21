const { priceLabel } = require("../api/public-display.js");
const workView = (work) => ({
  ...work,
  businessLabel: work.businessLabels.join(" · "),
});
const storyLines = (description) =>
  String(description || "")
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
const brandView = (brand) => {
  const source = brand || {};
  const lines = storyLines(source.description);
  const titleLines = lines.length > 1 ? lines.slice(1) : lines;
  return {
    ...source,
    initial: source.name ? source.name.slice(0, 1) : "",
    coverKicker: lines.length > 1 ? lines[0] : "",
    coverTitleLines: titleLines.map((text, index) => ({
      key: String(index),
      text,
    })),
    team: (source.team || []).map((member) => ({
      ...member,
      initial: member.name ? String(member.name).slice(0, 1) : "",
    })),
  };
};
const teamView = (team) =>
  team.map((member) => ({ ...member, initial: member.name.slice(0, 1) }));
const homeView = (home, priceLines) => ({
  ...home,
  brand: brandView(home.brand),
  featuredWorks: home.featuredWorks.map(workView),
  secondaryWorks: home.featuredWorks.slice(1, 3).map(workView),
  packages: (home.packages || []).map((pkg) => ({
    ...pkg,
    priceLabel: priceLabel(pkg, priceLines),
  })),
});
const serviceViews = (groups, priceLines) =>
  groups.map((group, index) => ({
    ...group,
    number: String(index + 1).padStart(2, "0"),
    packages: group.packages.map((pkg) => ({
      ...pkg,
      priceLabel: priceLabel(pkg, priceLines),
      cardCover: pkg.coverMedia || group.coverMedia || null,
    })),
  }));
const rows = (values) =>
  values.map((text, index) => ({ key: String(index), text }));
const packageView = (pkg) => ({
  ...pkg,
  serviceRows: rows(pkg.serviceItems),
  tipRows: rows(pkg.tips),
  noticeRows: rows(pkg.notices),
});
module.exports = {
  workView,
  brandView,
  teamView,
  homeView,
  serviceViews,
  packageView,
};
