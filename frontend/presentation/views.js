const { priceLabel } = require("../api/public-display.js");
const workView = (work) => ({
  ...work,
  businessLabel: work.businessLabels.join(" · "),
});
const brandView = (brand) => ({
  ...brand,
  initial: brand.name ? brand.name.slice(0, 1) : "",
});
const teamView = (team) =>
  team.map((member) => ({ ...member, initial: member.name.slice(0, 1) }));
const homeView = (home) => ({
  ...home,
  brand: brandView(home.brand),
  featuredWorks: home.featuredWorks.map(workView),
  secondaryWorks: home.featuredWorks.slice(1, 3).map(workView),
});
const serviceViews = (groups) =>
  groups.map((group, index) => ({
    ...group,
    number: String(index + 1).padStart(2, "0"),
    packages: group.packages.map((pkg) => ({
      ...pkg,
      priceLabel: priceLabel(pkg),
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
