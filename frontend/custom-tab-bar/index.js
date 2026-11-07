Component({
  data: {
    selected: 0,
    list: [
      {
        pagePath: "/pages/index/index",
        text: "",
        iconPath: "/static/icons/tab-home.png",
        selectedIconPath: "/static/icons/tab-home-active.png",
      },
      {
        pagePath: "/pages/works/works",
        text: "",
        iconPath: "/static/icons/tab-works.png",
        selectedIconPath: "/static/icons/tab-works-active.png",
      },
      {
        pagePath: "/pages/package/package",
        text: "",
        iconPath: "/static/icons/tab-services.png",
        selectedIconPath: "/static/icons/tab-services-active.png",
      },
      {
        pagePath: "/pages/about/about",
        text: "",
        iconPath: "/static/icons/tab-about.png",
        selectedIconPath: "/static/icons/tab-about-active.png",
      },
      {
        pagePath: "/pages/mine/mine",
        text: "",
        iconPath: "/static/icons/tab-mine.png",
        selectedIconPath: "/static/icons/tab-mine-active.png",
      },
    ],
  },
  methods: {
    switchTab(event) {
      const index = Number(event.currentTarget.dataset.index);
      const path = event.currentTarget.dataset.path;
      if (!path || Number.isNaN(index)) return;
      this.setData({ selected: index });
      wx.switchTab({ url: path });
    },
  },
});
