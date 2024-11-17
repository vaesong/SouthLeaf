<template>
	<view class="my-page">
		<text>test --- 我的</text>
		<!-- 底部信息区域 -->
		<FooterInfo />
	</view>
</template>

<script>
import FooterInfo from '@/components/FooterInfo/FooterInfo.vue'
import ModalPopup from "@/components/ModalPopup/ModalPopup.vue";

export default {
	components: {
		FooterInfo,
		ModalPopup
	},
	data() {
		return {
		};
	},
	methods: {
	}
};
</script>


<style scoped>
@import url("my.css")
</style>
