<template>
	<view class="waterfall-item">
		<slot name="default"></slot>
	</view>
</template>

<script>
	import { wait } from './js/utils.js';
	export default {
		name:"helangWaterfallItem",
		options:{
			virtualHost: true
		},
		props:{
			colIndex:{
				type:Number,
				default:0
			}
		},
		data() {
			return {
				
			};
		},
		mounted() {
			this.postHeight();
		},
		methods:{
			async postHeight(){
				// 添加一个异步的宏任务，部分低性能的移动设备会因为渲染问题导致上报的高度存在错误问题
				await wait(10);
				const query = uni.createSelectorQuery().in(this);
				query.select('.waterfall-item').boundingClientRect(data => {
					let height = Math.floor(data.height);
					this.$emit("height",{
						colIndex:this.$props.colIndex,
						height
					});
				}).exec();
			}
		}
	}
</script>

<style lang="scss" scoped>
	.waterfall-item{
		& + .waterfall-item{
			margin-top:20rpx;
		}
	}
</style>
