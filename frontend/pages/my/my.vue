<template>
  <view class="my-page">
    <!-- 顶部用户信息 -->
    <view class="user-info">
      <view class="user-details">
        <text class="user-name">momo</text>
        <text class="user-phone">01234567891</text>
      </view>
      <image class="user-avatar" src="/static/banner/logo.png" mode="aspectFill" />
    </view>

    <!-- 订单状态部分 -->
    <view class="order-status">
      <view class="status-item" @click="onItemClick()">
        <image class="status-icon" src="/static/icons/unpaid.png" />
        <text class="status-text">未付款</text>
      </view>
      <view class="status-item" @click="onItemClick()">
        <image class="status-icon" src="/static/icons/inprogress.png" />
        <text class="status-text">进行中</text>
      </view>
      <view class="status-item" @click="onItemClick()">
        <image class="status-icon" src="/static/icons/completed.png" />
        <text class="status-text">已完成</text>
      </view>
      <view class="status-item" @click="onItemClick()">
        <image class="status-icon" src="/static/icons/allorders.png" />
        <text class="status-text">全部订单</text>
      </view>
    </view>

    <!-- 功能列表 -->
    <view class="function-list">
			<view class="function-item" @click="onItemClick()">
				<text class="function-text">我的相册</text>
				<UniIcons type="arrowright" size="20" color="#888" class="arrow-icon" />
			</view>
			<view class="function-item" @click="onItemClick()">
				<text class="function-text">我的喜欢</text>
				<UniIcons type="arrowright" size="20" color="#888" class="arrow-icon" />
			</view>
			<view class="function-item" @click="onItemClick()">
				<text class="function-text">我的积分</text>
				<UniIcons type="arrowright" size="20" color="#888" class="arrow-icon" />
			</view>
			<view class="function-item" @click="onItemClick()">
				<text class="function-text">我的优惠券</text>
				<UniIcons type="arrowright" size="20" color="#888" class="arrow-icon" />
			</view>
			<view class="function-item" @click="onItemClick()">
				<text class="function-text">我的会员卡</text>
				<UniIcons type="arrowright" size="20" color="#888" class="arrow-icon" />
			</view>
		</view>

  </view>
</template>

<script>
import FooterInfo from '@/components/FooterInfo/FooterInfo.vue'
import ModalPopup from "@/components/ModalPopup/ModalPopup.vue";
import UniIcons from '@/uni_modules/uni-icons/components/uni-icons/uni-icons.vue';

export default {
	components: {
		FooterInfo,
		ModalPopup,
		UniIcons
	},
	data() {
		return {
		};
	},
	methods: {
		onItemClick() {
			uni.showToast({
				title: '开发中，敬请期待( •̀ .̫ •́ )✧',
				icon: 'none',
				duration: 1000,
			});
    },
	}
};
</script>


<style scoped>
@import url("my.css")
</style>
