<template>
  <view class="footer-info">
    <!-- 底部图片 -->
    <image src="/static/FooterInfo/foot.png" class="footer-background" mode="aspectFill" />

    <!-- 半透明覆盖层 -->
    <view class="footer-overlay">
      <view class="contact-info-group">
        <text class="contact-info">微信: 902600 <text class="link" @click="openWeChat">添加客服</text></text>
        <text class="contact-info">电话: 4008-767-212</text>
        <text class="contact-info">地址: 广东省东莞市凤岗镇保利百合花园8栋</text>
      </view>

      <view class="icon-buttons">
        <button class="icon-button" @click="openWeChat">
          <image src="/static/icons/weixin2.png" mode="aspectFit" class="icon-image"></image>
        </button>
        <button class="icon-button" @click="openXiaoHongShu">
          <image src="/static/icons/xiaohongshu1.png" mode="aspectFit" class="icon-image"></image>
        </button>
        <button class="icon-button" @click="openWeibo">
          <image src="/static/icons/xinlangweibo.png" mode="aspectFit" class="icon-image"></image>
        </button>
        <button class="icon-button" @click="openDouYin">
          <image src="/static/icons/douyin.png" mode="aspectFit" class="icon-image"></image>
        </button>
      </view>

      <view class="footer-brand">
        <text>枝南影像</text>
        <text class="footer-version">v0.1.0</text>
      </view>
    </view>
  </view>

  <ModalPopup
      v-if="showWeChat"
      :isVisible="showWeChat"
      title="枝南影像"
      imageSrc="/static/info/wechat.png"
      description="长按添加微信"
      @close="showWeChat = false"
    />

    <ModalPopup
      v-if="showXiaoHongShu"
      :isVisible="showXiaoHongShu"
      title="枝南影像"
      imageSrc="/static/info/weixin.png"
      description="关注小红书账号"
      @close="showXiaoHongShu = false"
    />

    <ModalPopup
      v-if="showWeibo"
      :isVisible="showWeibo"
      title="枝南影像"
      imageSrc="/static/info/weixin.png"
      description="关注微博账号"
      @close="showWeibo = false"
    />

    <ModalPopup
      v-if="showDouYin"
      :isVisible="showDouYin"
      title="枝南影像"
      imageSrc="/static/info/weixin.png"
      description="关注抖音账号"
      @close="showDouYin = false"
    />
</template>
  
<script>
  import ModalPopup from "@/components/ModalPopup/ModalPopup.vue";

  export default {
    components: {
      ModalPopup
    },
    data(){
      return {
        showWeChat: false,
        showXiaoHongShu: false,
        showWeibo: false,
        showDouYin: false,
      }
    },
    methods: {
      handleLinkClick() {
        // 处理链接点击事件，例如跳转到官网等
      },
      openWeChat() {
        this.showWeChat = true; // 显示自定义弹出层
      },
      closeWeChat() {
        this.showWeChat = false; // 隐藏自定义弹出层
      },
      openXiaoHongShu() {
        this.showXiaoHongShu = true;
      },
      closeXiaoHongShu() {
        this.showXiaoHongShu = false;
      },
      openWeibo() {
        this.showWeibo = true;
      },
      closeWeibo() {
        this.showWeibo = false;
      },
      openDouYin() {
        this.showDouYin = true;
      },
      closeDouYin() {
        this.showDouYin = false;
      },
    }
  };
</script>
  
<style scoped>
  @import url("FooterInfo.css");
</style>
  