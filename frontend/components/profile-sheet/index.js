const { sheetModel } = require("../../user/profile.js");

Component({
  properties: {
    show: { type: Boolean, value: false },
    nickname: { type: String, value: "" },
    hasAvatar: { type: Boolean, value: false },
    avatarSrc: { type: String, value: "" },
    askName: { type: Boolean, value: false },
    askAvatar: { type: Boolean, value: false },
    lift: { type: Boolean, value: false },
  },
  data: sheetModel({}),
  observers: {
    "show, nickname, hasAvatar, askName, askAvatar, avatarSrc"() {
      const opening = this.data.show && !this._opened;
      this._opened = Boolean(this.data.show);
      if (!this.data.show) return;
      this.setData(
        sheetModel({
          nickname: this.data.nickname,
          hasAvatar: this.data.hasAvatar,
          askName: this.data.askName,
          askAvatar: this.data.askAvatar,
          avatarSrc: this.data.avatarSrc,
          draftName: opening ? this.data.nickname : this.data.draftName,
          chosenPath: opening ? "" : this.data.chosenPath,
        }),
      );
    },
  },
  methods: {
    onChooseAvatar(event) {
      const path = event.detail && event.detail.avatarUrl;
      if (!path) return;
      this.setData(sheetModel({ ...this.fields(), draftName: this.data.draftName, chosenPath: path }));
    },
    onName(event) {
      const value = event.detail && event.detail.value ? event.detail.value : "";
      this.setData(
        sheetModel({ ...this.fields(), draftName: value, chosenPath: this.data.chosenPath }),
      );
    },
    onSave() {
      if (!this.data.ready) return;
      this.triggerEvent("save", {
        displayName: (this.data.draftName || "").trim(),
        avatarPath: this.data.chosenPath || "",
      });
    },
    onDismiss() {
      this.triggerEvent("dismiss");
    },
    fields() {
      return {
        nickname: this.data.nickname,
        hasAvatar: this.data.hasAvatar,
        askName: this.data.askName,
        askAvatar: this.data.askAvatar,
        avatarSrc: this.data.avatarSrc,
      };
    },
  },
});
