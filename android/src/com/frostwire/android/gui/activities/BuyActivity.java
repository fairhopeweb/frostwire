/*
 * Created by Angel Leon (@gubatron), Alden Torres (aldenml),
 *            Marcelina Knitter (marcelinkaaa)
 * Copyright (c) 2011-2022, FrostWire(R). All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.frostwire.android.gui.activities;

import static com.frostwire.android.util.Asyncs.async;

import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.android.billingclient.api.PurchasesUpdatedListener;
import com.frostwire.android.BuildConfig;
import com.frostwire.android.R;
import com.frostwire.android.core.ConfigurationManager;
import com.frostwire.android.core.Constants;
import com.frostwire.android.gui.util.UIUtils;
import com.frostwire.android.gui.views.AbstractActivity;
import com.frostwire.android.gui.views.PaymentOptionsVisibility;
import com.frostwire.android.gui.views.ProductCardView;
import com.frostwire.android.gui.views.ProductPaymentOptionsView;
import com.frostwire.android.offers.Offers;
import com.frostwire.android.offers.PlayStore;
import com.frostwire.android.offers.Product;
import com.frostwire.android.offers.Products;
import com.frostwire.android.util.SystemUtils;
import com.frostwire.util.Logger;
import com.frostwire.util.Ref;

import java.lang.ref.WeakReference;

/**
 * @author gubatron
 * @author aldenml
 */
public final class BuyActivity extends AbstractActivity {

    private static final Logger LOG = Logger.getLogger(BuyActivity.class);

    public static final String INTERSTITIAL_MODE = "interstitial_mode";
    public static final int PURCHASE_SUCCESSFUL_RESULT_CODE = 0xaadd;
    public static final String EXTRA_KEY_PURCHASE_TIMESTAMP = "purchase_timestamp";

    private static final String LAST_SELECTED_CARD_ID_KEY = "last_selected_card_view_id";
    private static final String PAYMENT_OPTIONS_VISIBILITY_KEY = "payment_options_visibility";
    private static final String OFFER_ACCEPTED = "offer_accepted";

    private static int REWARD_FREE_AD_MINUTES = Constants.MIN_REWARD_AD_FREE_MINUTES;

    private ProductCardView cardNminutes;
    private ProductCardView card30days;
    private ProductCardView card1year;
    private ProductCardView selectedProductCard;

    /**
     * the view with the "Buy" buttons for subscription or one time, which is reused for the PlayStore purchase card views
     */
    private ProductPaymentOptionsView paymentOptionsView;

    private boolean offerAccepted;

    public BuyActivity() {
        super(R.layout.activity_buy);
        async(BuyActivity::getRewardFreeAdMinutesFromConfigTask, new WeakReference<>(this)); // in the meantime use the default value Constants.MIN_REWARD_AD_FREE_MINUTES
    }

    private static void getRewardFreeAdMinutesFromConfigTask(WeakReference<BuyActivity> buyActivityRef) {
        REWARD_FREE_AD_MINUTES = ConfigurationManager.instance().getInt(Constants.PREF_KEY_GUI_REWARD_AD_FREE_MINUTES);
        SystemUtils.postToUIThread(() -> {
            try {
                if (Ref.alive(buyActivityRef)) {
                    buyActivityRef.get().refreshPaymentOptionsViewRewardMinutesTextView();
                }
            } catch (Throwable t) {
                if (BuildConfig.DEBUG) {
                    throw t;
                }
                LOG.error("BuyActivity::getRewardFreeAdMinutesFromConfigTask() posting to main looper: " + t.getMessage(), t);
            }
        });
    }

    private void refreshPaymentOptionsViewRewardMinutesTextView() {
        SystemUtils.ensureUIThreadOrCrash("BuyActivity::refreshPaymentOptionsViewRewardMinutesTextView");
        if (paymentOptionsView != null) {
            TextView textView = paymentOptionsView.findViewById(R.id.view_product_payment_options_temporary_ad_removal_description_textview);
            String temporary_ad_removal_description = textView.getResources().getString(R.string.temporary_ad_removal_description, REWARD_FREE_AD_MINUTES);
            textView.setText(temporary_ad_removal_description);
        }
    }

    private final PurchasesUpdatedListener onPurchasesUpdatedListener = (billingResult, purchases) -> BuyActivity.this.onPurchasesUpdatedOld(billingResult.getResponseCode());

    private void purchaseProduct(int tagId) {
        Product p = (Product) selectedProductCard.getTag(tagId);
        if (p != null) {
            PlayStore playStore = PlayStore.getInstance(this);
            if (playStore == null) {
                LOG.error("purchaseProduct(tagId=" + tagId + ") failed, could not get an instance of PlayStore");
                return;
            }
            playStore.setGlobalPurchasesUpdatedListenerWeakRef(onPurchasesUpdatedListener);
            playStore.purchase(this, p);
        }
    }

    @Override
    protected void initToolbar(Toolbar toolbar) {
        toolbar.setTitle(getActionBarTitle());
    }

    @Override
    protected void initComponents(Bundle savedInstanceState) {
        WeakReference<AppCompatActivity> activityRef = Ref.weak(this);
        SystemUtils.postToHandler(SystemUtils.HandlerThreadName.MISC, () -> Offers.preLoadRewardedVideoAsync(activityRef));
        final boolean interstitialMode = isInterstitial();
        offerAccepted = savedInstanceState != null &&
                savedInstanceState.containsKey(OFFER_ACCEPTED) &&
                savedInstanceState.getBoolean(OFFER_ACCEPTED, false);
        if (interstitialMode) {
            initInterstitialModeActionBar(getActionBarTitle());
        }

        initSubscriptionsButton();
        initOfferLayer(interstitialMode);
        initProductCards(getLastSelectedCardViewId(savedInstanceState));
        initPaymentOptionsView(getLastPaymentOptionsViewVisibility(savedInstanceState));

        // If Google Store not ready or available, auto-select rewarded ad option
        if (card30days.getVisibility() == View.GONE ||
                card1year.getVisibility() == View.GONE) {
            cardNminutes.performClick();
        }

        SystemUtils.postToHandler(SystemUtils.HandlerThreadName.MISC, () -> {
            boolean paused = Offers.adsPausedAsync();
            SystemUtils.postToUIThread(() -> onAdsPausedAsyncFinished(paused, activityRef));
        });
    }

    private void initSubscriptionsButton() {
        // Only for Google Play version
        if (!Constants.IS_GOOGLE_PLAY_DISTRIBUTION)
            return;

        Button subscriptionsButton = findView(R.id.activity_buy_products_subscriptions_button);
        if (subscriptionsButton != null) {
            subscriptionsButton.setVisibility(View.VISIBLE);
            subscriptionsButton.setOnClickListener(v -> UIUtils.openURL(v.getContext(), "https://play.google.com/store/account/subscriptions"));
        }
    }

    private static void onAdsPausedAsyncFinished(boolean adsPaused, WeakReference<AppCompatActivity> activityRef) {
        SystemUtils.ensureUIThreadOrCrash("BuyActivity::onAdsPausedAsyncFinished");
        if (adsPaused) {
            // we shouldn't be here if ads have been paused, do not load rewarded videos
            return;
        }
        if (!Ref.alive(activityRef)) {
            LOG.info("onAdsPausedAsyncFinished (adsPaused=" + adsPaused + ") aborted, lost reference to BuyActivity");
            return;
        }
        LOG.info("onAdsPausedAsyncFinished: ads aren't paused, have not yet fetched the rewarded video, going for it...");
        SystemUtils.postToHandler(SystemUtils.HandlerThreadName.MISC, () -> Offers.preLoadRewardedVideoAsync(activityRef));
    }

    private String getActionBarTitle() {
        final String titlePrefix = getString(R.string.remove_ads);
        return titlePrefix + ". " + getString(UIUtils.randomPitchResId(false)) + ".";
    }

    private void initOfferLayer(boolean interstitialMode) {
        if (!interstitialMode) {
            View offerLayout = findView(R.id.activity_buy_interstitial_linear_layout);
            offerLayout.setVisibility(View.GONE);
            return;
        }

        // user rotates screen after having already accepted the offer
        if (offerAccepted) {
            View offerLayout = findView(R.id.activity_buy_interstitial_linear_layout);
            offerLayout.setVisibility(View.GONE);
            return;
        }

        final InterstitialOfferDismissButtonClickListener dismissOfferClickListener = new InterstitialOfferDismissButtonClickListener();
        ImageButton dismissButton = findView(R.id.activity_buy_interstitial_dismiss_button);
        dismissButton.setOnClickListener(dismissOfferClickListener);

        final OfferClickListener offerClickListener = new OfferClickListener();
        View offerLayout = findView(R.id.activity_buy_interstitial_linear_layout);
        offerLayout.setOnClickListener(offerClickListener);

        final TextView randomPitch = findView(R.id.activity_buy_interstitial_random_pitch);
        randomPitch.setText(UIUtils.randomPitchResId(true));
    }


    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        scrollToSelectedCard(selectedProductCard);
    }

    @Override
    public void onBackPressed() {
        if (isInterstitial()) {
            onInterstitialActionBarDismiss();
            finish();
        } else {
            finish();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // initComponents() and setToolbar() have been called

        // from DEEP-Link House Ad pointing to app://com.frostwire.android/remove-ads
        Intent intent = getIntent();
        if (intent != null) {
            Uri data = intent.getData();
            if (data != null && "app://com.frostwire.android/remove-ads".equals(data.toString()) && cardNminutes != null) {
                cardNminutes.performClick();
            }
        }
    }

    private void onInterstitialActionBarDismiss() {
        if (isInterstitial()) {
            Offers.AdNetworkHelper.dismissAndOrShutdownIfNecessary(
                    this,
                    false,
                    false,
                    false,
                    getApplication());
        }
    }

    private void initInterstitialModeActionBar(String title) {
        View v = findView(R.id.activity_buy_actionbar_interstitial);
        v.setVisibility(View.VISIBLE);

        View toolbar = findToolbar();
        toolbar.setVisibility(View.GONE);

        TextView titleTextView = findView(R.id.activity_buy_actionbar_interstitial_buy_activity_title);
        titleTextView.setText(title);

        ImageButton closeButton = findView(R.id.activity_buy_actionbar_interstitial_buy_activity_dismiss_button);
        closeButton.setOnClickListener(new InterstitialActionBarDismissButtonClickListener());
    }

    private void initProductCards(int lastSelectedCardViewId) {
        View.OnClickListener cardClickListener = new ProductCardViewOnClickListener();
        card30days = findView(R.id.activity_buy_product_card_30_days);
        card1year = findView(R.id.activity_buy_product_card_1_year);
        cardNminutes = findView(R.id.activity_buy_product_card_reward);

        if (REWARD_FREE_AD_MINUTES > 0) {
            initRewardCard(cardNminutes);
            cardNminutes.setVisibility(View.VISIBLE);
            cardNminutes.setOnClickListener(cardClickListener);
        } else {
            cardNminutes.setVisibility(View.GONE);
        }

        PlayStore store = PlayStore.getInstance(this);

        if (Constants.IS_GOOGLE_PLAY_DISTRIBUTION || Constants.IS_BASIC_AND_DEBUG) {
            initProductCard(card30days, store, Products.SUBS_DISABLE_ADS_1_MONTH_SKU, null);
            initProductCard(card1year, store, Products.SUBS_DISABLE_ADS_1_YEAR_SKU, null);
            card30days.setOnClickListener(cardClickListener);
            card1year.setOnClickListener(cardClickListener);
        } else {
            card30days.setVisibility(View.GONE);
            card1year.setVisibility(View.GONE);
        }

        initLastCardSelection(lastSelectedCardViewId);
    }

    private void initLastCardSelection(int lastSelectedCardViewId) {
        selectedProductCard = getSelectedProductCard(lastSelectedCardViewId, this);
        highlightSelectedCard(selectedProductCard);
    }

    private void initPaymentOptionsView(int paymentOptionsVisibility) {
        paymentOptionsView = findView(R.id.activity_buy_product_payment_options_view);
        paymentOptionsView.setVisibility(paymentOptionsVisibility);
        paymentOptionsView.setOnBuyListener(new ProductPaymentOptionsView.OnBuyListener() {
            @Override
            public void onAutomaticRenewal() {
                purchaseProduct(R.id.subs_product_tag_id);
            }

            @Override
            public void onOneTime() {
                purchaseProduct(R.id.inapp_product_tag_id);
            }

            @Override
            public void onRewardedVideo() {
                playRewardedVideo();
            }
        });

        if (paymentOptionsVisibility == View.VISIBLE) {
            showPaymentOptionsBelowSelectedCard(selectedProductCard);
        }
    }

    private void playRewardedVideo() {
        paymentOptionsView.startProgressBar(ProductPaymentOptionsView.PayButtonType.REWARD_VIDEO);
        Offers.showRewardedVideo(this);
    }

    private void initRewardCard(ProductCardView card) {
        if (card == null) {
            throw new IllegalArgumentException("card argument can't be null");
        }

        final Resources resources = card.getResources();
        final String reward_product_title = (resources != null) ?
                String.format(resources.getString(R.string.reward_product_title), REWARD_FREE_AD_MINUTES) :
                String.format("%d minutes", REWARD_FREE_AD_MINUTES);
        final String reward_product_description = (resources != null) ?
                resources.getString(R.string.ad_free) : "Ad-free";
        final String reward_product_price = (resources != null) ?
                resources.getString(R.string.reward_product_price) :
                "Free, Play 1 Video Ad";

        card.setPaymentOptionsVisibility(new PaymentOptionsVisibility(false, false, true));
        Product productReward = new Product() {
            @Override
            public String sku() {
                return Products.REWARDS_DISABLE_ADS_MINUTES_SKU;
            }

            @Override
            public boolean subscription() {
                return false;
            }

            @Override
            public String title() {
                return reward_product_title;
            }

            @Override
            public String description() {
                return reward_product_description;
            }

            @Override
            public String price() {
                return reward_product_price;
            }

            @Override
            public String currency() {
                return "";
            }

            @Override
            public boolean purchased() {
                return false;
            }

            @Override
            public long purchaseTime() {
                return REWARD_FREE_AD_MINUTES * 60_000L;
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public boolean enabled(String feature) {
                return true;
            }
        };
        card.setTag(R.id.reward_product_tag_id, productReward);
        card.updateData(productReward);
        card.updateTitle(productReward.title());
    }

    private void initProductCard(ProductCardView card,
                                 PlayStore store,
                                 String subsSKU,
                                 String inappSKU) {
        if (card == null) {
            throw new IllegalArgumentException("card argument can't be null");
        }
        // let's do this here to avoid a rare NPE that's popping up on ProductPaymentOptionsView::refreshOptionsVisibility
        card.setPaymentOptionsVisibility(new PaymentOptionsVisibility(false, true, false));

        if (store == null) {
            throw new IllegalArgumentException("store argument can't be null");
        }
        if (subsSKU == null) {
            throw new IllegalArgumentException("subsSKU argument can't be null");
        }
        final boolean includeOnetimeProducts = inappSKU != null;
        final Product prodSubs = store.product(subsSKU);
        final Product prodInApp = includeOnetimeProducts ? store.product(inappSKU) : null;
        if (prodSubs == null && prodInApp == null) {
            card.setVisibility(View.GONE);
            return;
        }
        card.setTag(R.id.subs_product_tag_id, prodSubs);
        card.setTag(R.id.inapp_product_tag_id, prodInApp);
        card.setPaymentOptionsVisibility(new PaymentOptionsVisibility(includeOnetimeProducts, true, false));

        card.updateData(prodSubs);
    }

    @SuppressWarnings("NullableProblems")
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (outState != null) {
            outState.putInt(LAST_SELECTED_CARD_ID_KEY, selectedProductCard.getId());
            outState.putInt(PAYMENT_OPTIONS_VISIBILITY_KEY, paymentOptionsView.getVisibility());
            outState.putBoolean(OFFER_ACCEPTED, offerAccepted);
        }
        super.onSaveInstanceState(outState);
    }

    private int getLastSelectedCardViewId(Bundle savedInstanceState) {
        int lastSelectedCardViewId = -1;
        if (savedInstanceState != null) {
            if (savedInstanceState.containsKey(LAST_SELECTED_CARD_ID_KEY)) {
                lastSelectedCardViewId = savedInstanceState.getInt(LAST_SELECTED_CARD_ID_KEY);
            }
        }
        return lastSelectedCardViewId;
    }

    private int getLastPaymentOptionsViewVisibility(Bundle savedInstanceState) {
        int paymentOptionsVisibility = View.GONE;
        if (savedInstanceState != null) {
            if (savedInstanceState.containsKey(PAYMENT_OPTIONS_VISIBILITY_KEY)) {
                paymentOptionsVisibility = savedInstanceState.getInt(PAYMENT_OPTIONS_VISIBILITY_KEY);
            }
        }
        return paymentOptionsVisibility;
    }

    private void highlightSelectedCard(ProductCardView productCardView) {
        if (productCardView == null) {
            return;
        }
        card30days.setSelected(productCardView == card30days);
        card1year.setSelected(productCardView == card1year);
        cardNminutes.setSelected(productCardView == cardNminutes);
    }

    private void scrollToSelectedCard(ProductCardView productCardView) {
        ScrollView scrollView = findView(R.id.activity_buy_scrollview);
        LinearLayout linearLayout = (LinearLayout) scrollView.getChildAt(0);
        int index = linearLayout.indexOfChild(productCardView);
        int cardHeight = productCardView.getHeight() + productCardView.getPaddingTop();
        scrollView.scrollTo(0, index * cardHeight);
    }

    private void showPaymentOptionsBelowSelectedCard(ProductCardView productCardView) {
        paymentOptionsView.refreshOptionsVisibility(productCardView);
        final ViewGroup scrollView = findView(R.id.activity_buy_scrollview);
        final ViewGroup layout = (ViewGroup) scrollView.getChildAt(0);
        if (layout != null) {
            int selectedCardIndex = layout.indexOfChild(productCardView);
            final int paymentOptionsViewIndex = layout.indexOfChild(paymentOptionsView);

            if (paymentOptionsView.getVisibility() == View.VISIBLE) {
                if (paymentOptionsViewIndex - 1 == selectedCardIndex) {
                    // no need to animate payment options on the same card
                    // where it's already shown.
                    return;
                }

                paymentOptionsView.animate().setDuration(200)
                        .scaleY(0).setInterpolator(new DecelerateInterpolator())
                        .withEndAction(() -> scaleDownPaymentOptionsView(layout))
                        .start();
            } else {
                // first time shown
                scaleDownPaymentOptionsView(layout);
            }
        }
    }

    // where the actual removal of the view and re-addition to the layout happens.
    private void scaleDownPaymentOptionsView(final ViewGroup layout) {
        layout.removeView(paymentOptionsView);
        int selectedCardIndex = layout.indexOfChild(selectedProductCard);
        paymentOptionsView.setVisibility(View.VISIBLE);
        layout.addView(paymentOptionsView, selectedCardIndex + 1);
        paymentOptionsView.animate().setDuration(200)
                .scaleY(1).setInterpolator(new DecelerateInterpolator())
                .start();
    }

    public void stopProgressbars(ProductPaymentOptionsView.PayButtonType payButtonType) {
        paymentOptionsView.stopProgressBar(payButtonType);
    }

    private static ProductCardView getSelectedProductCard(int productCardViewId, BuyActivity buyActivity) {
        if (buyActivity == null) {
            throw new IllegalArgumentException("BuyActivity::getSelectedProductCard(productCardViewId=" + productCardViewId + ", buyActivity=null!!!)");
        }
        ProductCardView productCard;
        if (productCardViewId == R.id.activity_buy_product_card_30_days) {
            productCard = buyActivity.card30days;
        } else if (productCardViewId == R.id.activity_buy_product_card_reward) {
            productCard = buyActivity.cardNminutes;
        } else {
            productCard = buyActivity.card1year;
        }
        return productCard;
    }

    private class ProductCardViewOnClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (v instanceof ProductCardView) {
                int id = v.getId();
                BuyActivity buyActivity = (BuyActivity) v.getContext();
                ProductCardView selectedProductCard = BuyActivity.getSelectedProductCard(id, buyActivity);
                buyActivity.selectedProductCard = selectedProductCard;
                highlightSelectedCard(selectedProductCard);
                showPaymentOptionsBelowSelectedCard(selectedProductCard);
                scrollToSelectedCard(selectedProductCard);

            }
        }
    }

    private boolean isInterstitial() {
        Intent intent = getIntent();
        return intent != null && intent.getBooleanExtra(INTERSTITIAL_MODE, false);
    }

    private void onPurchasesUpdatedOld(int responseCode) {
        // RESPONSE_CODE = 0 -> Payment Successful
        // user clicked outside of the PlayStore purchase dialog
        if (responseCode != 0) {
            paymentOptionsView.stopProgressBar();

            LOG.info("BuyActivity local receiver -> purchase cancelled");
            return;
        }

        // make sure ads won't show on this session any more if we got a positive response.
        Offers.stopAdNetworks(this);

        LOG.info("BuyActivity local receiver -> purchase finished");
        finish();
    }

    private class InterstitialActionBarDismissButtonClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            onInterstitialActionBarDismiss();
            finish();
        }
    }

    private class InterstitialOfferDismissButtonClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            offerAccepted = false;
            onInterstitialActionBarDismiss();
            finish();
        }
    }

    private class OfferClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            final View offerLayout = findView(R.id.activity_buy_interstitial_linear_layout);
            offerAccepted = true;
            offerLayout.animate().setDuration(500)
                    .translationY(offerLayout.getBottom()).setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> offerLayout.setVisibility(View.GONE))
                    .start();
        }
    }
}
