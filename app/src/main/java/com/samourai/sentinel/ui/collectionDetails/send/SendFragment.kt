package com.samourai.sentinel.ui.collectionDetails.send

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewModelScope
import androidx.transition.TransitionManager
import com.google.android.material.transition.MaterialContainerTransform
import com.google.zxing.BarcodeFormat
import com.google.zxing.WriterException
import com.google.zxing.client.android.Contents
import com.google.zxing.client.android.encode.QRCodeEncoder
import com.samourai.sentinel.R
import com.samourai.sentinel.data.AddressTypes
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.repository.ExchangeRateRepository
import com.samourai.sentinel.data.repository.FeeRepository
import com.samourai.sentinel.databinding.FragmentSpendBinding
import com.samourai.sentinel.send.SuggestedFee
import com.samourai.sentinel.ui.broadcast.BroadcastTx
import com.samourai.sentinel.ui.utils.AndroidUtil
import com.samourai.sentinel.ui.utils.hideKeyboard
import com.samourai.sentinel.ui.views.codeScanner.CameraFragmentBottomSheet
import com.samourai.sentinel.util.FormatsUtil
import com.samourai.sentinel.util.MonetaryUtil
import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import com.sparrowwallet.hummingbird.registry.RegistryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.util.encoders.Hex
import org.koin.java.KoinJavaComponent.inject
import timber.log.Timber
import java.io.File
import java.math.BigInteger
import java.text.DecimalFormat
import java.util.*
import kotlin.math.ceil


class SendFragment : Fragment() {

    private var isBTCEditing = false
    private var isFiatEditing = false
    private var amount: Double = 0.0
    private var transactionComposer = TransactionComposer()
    private var rate: ExchangeRateRepository.Rate = ExchangeRateRepository.Rate("", 0)
    private val exchangeRateRepository: ExchangeRateRepository by inject(ExchangeRateRepository::class.java)
    private var address: String = ""
    private val feeRepository: FeeRepository by inject(FeeRepository::class.java)
    private var feeLow: Long = 0L
    private var feeMed: Long = 0L
    private var feeHigh: Long = 0L
    var mCollection: PubKeyCollection? = null
    private val decimalFormat = DecimalFormat("##.00")
    private var qrCodeString: String? = null

    private var _fragmentSpendBinding: FragmentSpendBinding? = null
    private val fragmentSpendBinding get() = _fragmentSpendBinding!!

    private val viewModel: SendViewModel by viewModels()

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        _fragmentSpendBinding = FragmentSpendBinding.inflate(inflater, container, false)
        val view = fragmentSpendBinding.root
        return view
    }


    @RequiresApi(Build.VERSION_CODES.R)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        exchangeRateRepository.getRateLive().observe(this.viewLifecycleOwner) {
            rate = it
            DecimalFormat.getNumberInstance().currency = Currency.getInstance(rate.currency)
        }

        watchAddressAndAmount()

        fragmentSpendBinding.toEditText.setEndIconOnClickListener {
            val string = AndroidUtil.getClipBoardString(requireContext())
            fragmentSpendBinding.btcAddress.setText(string)
        }

        fragmentSpendBinding.composeBtn.setOnClickListener {
            containerTransform(fragmentSpendBinding.fragmentBroadcastTx.unsignedTxView, fragmentSpendBinding.composeBtn)
        }

        if (isAdded) {
            setPubKeySelector()
        }

        setUpFee()

        fragmentSpendBinding.fragmentBroadcastTx.broadCastTransactionBtn.setOnClickListener {
            val camera = CameraFragmentBottomSheet()
            camera.show(parentFragmentManager, camera.tag)
            camera.setQrCodeScanLisenter {
                val signedTxHex = it
                val intent = Intent(context, BroadcastTx::class.java).putExtra("signedTxHex", signedTxHex)
                startActivity(intent)
                camera.dismiss()
            }
            //startActivity(Intent(context, BroadcastFromComposeTx::class.java).putExtra("qrString", qrCodeString))
        }

        viewModel.psbtLive.observe(viewLifecycleOwner) {
            generateQRCode(it)
            qrCodeString = it
            fragmentSpendBinding.fragmentBroadcastTx.psbtText.text = it
            fragmentSpendBinding.fragmentBroadcastTx.psbtText.movementMethod = ScrollingMovementMethod()
        }

        viewModel.validSpend.observe(viewLifecycleOwner) {
            fragmentSpendBinding.composeBtn.isEnabled = it
            fragmentSpendBinding.composeBtn.alpha = if (it) 1f else 0.6f
        }

        fragmentSpendBinding.fragmentBroadcastTx.psbtCopyBtn.setOnClickListener {
            val psbt = viewModel.psbtLive.value
            if (psbt?.length == 0) {
                return@setOnClickListener
            }
            val cm = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
            val clipData = ClipData
                    .newPlainText("PSBT", psbt)
            if (cm != null) {
                cm.setPrimaryClip(clipData)
                Toast.makeText(context, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT)
                        .show()
            }
        }

        viewModel.minerFee.observe(viewLifecycleOwner) {
            fragmentSpendBinding.fragmentComposeTx.feeSelector.totalMinerFee.text = it
        }

        fragmentSpendBinding.fragmentBroadcastTx.psbtShareBtn.setOnClickListener { view1 ->
            val popup = PopupMenu(requireContext(), view1)
            popup.menuInflater.inflate(R.menu.send_share_menu, popup.menu)
            popup.setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.menu_share_as_text -> {
                        val psbtString = viewModel.psbtLive.value
                        val sendIntent: Intent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, psbtString)
                            type = "text/plain"
                        }
                        val shareIntent = Intent.createChooser(sendIntent, null)
                        startActivity(shareIntent)
                    }
                    R.id.menu_save_as_psbt -> {
                        sharePSBTFile()
                    }
                    R.id.menu_share_as_qr -> {

                    }
                }
                true
            }
            popup.show()
        }

        fragmentSpendBinding.composeBtn.setOnClickListener {
            it.hideKeyboard()
            if (viewModel.makeTx()) {
                requireActivity().window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.v3_accent)
                containerTransform(fragmentSpendBinding.fragmentBroadcastTx.unsignedTxView, fragmentSpendBinding.composeBtn)
            }
        }

        setUpToolbar()
    }

    private fun setUpToolbar() {
        fragmentSpendBinding.fragmentBroadcastTx.unsignedTxToolbar.setNavigationOnClickListener {
            containerTransform(fragmentSpendBinding.composeBtn, fragmentSpendBinding.fragmentBroadcastTx.unsignedTxView)
        }

        fragmentSpendBinding.sendAppBar.setNavigationOnClickListener {
            requireActivity().onBackPressed()
        }

        fragmentSpendBinding.sendAppBar.setOnMenuItemClickListener { menu ->
            if (menu.itemId == R.id.action_scan_qr) {
                val camera = CameraFragmentBottomSheet()
                camera.setQrCodeScanLisenter {
                    if (it.length < 100) {
                        fragmentSpendBinding.btcAddress.setText(it)
                    }
                    camera.dismiss()
                }
                camera.show(parentFragmentManager, camera.tag)
            }
            true
        }
    }

    private fun sharePSBTFile() {
        val psbt = viewModel.getPsbtBytes() ?: return
        val qrFile = "${requireContext().cacheDir.path}${File.separator}${UUID.randomUUID()}.psbt"

        val file = File(qrFile)
        if (file.exists()) {
            file.delete()
        }
        file.writeBytes(psbt)
        file.setReadable(true, false)
        val intent = Intent()
        intent.action = Intent.ACTION_SEND
        intent.type = "**/**"
        if (Build.VERSION.SDK_INT >= 24) {
            //From API 24 sending FIle on intent ,require custom file provider
            intent.putExtra(
                    Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                    requireContext(),
                    requireContext()
                            .packageName + ".provider", file
            )
            )
        } else {
            intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
        }
        startActivity(
                Intent.createChooser(
                        intent,
                        requireContext().getText(R.string.send_payment_code)
                )
        )

    }

    private fun containerTransform(enter: View, leaving: View) {
        val transform = MaterialContainerTransform()
        transform.scrimColor = Color.TRANSPARENT
        transform.drawingViewId = R.id.sendRootLayout

        val transition: MaterialContainerTransform = transform
        transition.startView = leaving
        transition.duration = 400
        transition.endView = enter

        transition.addTarget(enter)
        TransitionManager.beginDelayedTransition(view as ViewGroup, transition)
        enter.visibility = VISIBLE
        leaving.visibility = GONE
        if (leaving.id == fragmentSpendBinding.fragmentBroadcastTx.unsignedTxView.id) {
            requireActivity().window.statusBarColor =
                    ContextCompat.getColor(requireContext(), android.R.color.transparent)
        } else {
            requireActivity().window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.v3_accent)
        }
    }

    private fun watchAddressAndAmount() {
        fragmentSpendBinding.fiatEditTextLayout.hint = exchangeRateRepository.getRateLive().value?.currency
        fragmentSpendBinding.fiatEditText.addTextChangedListener { onFiatValueChange(it.toString(), fragmentSpendBinding.btcEditText.toString()) }
        fragmentSpendBinding.btcEditText.addTextChangedListener { onBtcValueChanged(it.toString()) }
        fragmentSpendBinding.btcAddress.addTextChangedListener {
            if (it.toString().isNotEmpty()) {
                address = it.toString().trim()
                if (FormatsUtil.isValidBitcoinAddress(address)) {
                    viewModel.setDestinationAddress(address)
                } else {
                    Toast.makeText(requireContext(), "Invalid address", Toast.LENGTH_SHORT).show()

                }
            } else {
                viewModel.setDestinationAddress("")
            }
        }
    }

    private fun onBtcValueChanged(btcString: String) {
        if (isBTCEditing) {
            return
        }
        try {
            if (btcString.isEmpty()) {
                setBtcEdit("")
                setFiatEdit("")
            }
            val btc: Double = btcString.toDouble()
            amount = btc
            if (btc > 21000000.0) {
                setFiatEdit("")
                setBtcEdit("")
                return
            }
            val fiatRate = btc.times(rate.rate)
            setFiatEdit(DecimalFormat.getNumberInstance().format(fiatRate))
            viewModel.setAmount(amount)
            print("Setting amount: " + amount)
        } catch (Ex: Exception) {

        }
    }

    private fun onFiatValueChange(fiatString: String, btcString: String) {
        if (isFiatEditing) {
            return
        }
        try {
            if (fiatString.isEmpty()) {
                setBtcEdit("")
                setFiatEdit("")
            }
            val fiat: Double = fiatString.toDouble()
            val btcRate = (1 / rate.rate.toFloat()) * fiat.toFloat()
            if (btcRate > 21000000.0) {
                setBtcEdit("")
                setFiatEdit("")
                return
            }

            setBtcEdit(MonetaryUtil.getInstance().formatToBtc((btcRate * 1e8).toLong()))
            setFiatEdit(DecimalFormat.getNumberInstance().format(fiat))
            val btc: Double = MonetaryUtil.getInstance().formatToBtc((btcRate * 1e8).toLong()).toDouble()
            amount = btc
            viewModel.setAmount(amount)
            print("Setting amount: " + amount)
        } catch (Ex: Exception) {

        }
    }

    private fun setBtcEdit(value: String) {
        isBTCEditing = true
        fragmentSpendBinding.btcEditText.setText(value)
        fragmentSpendBinding.btcEditText.text?.length?.let { fragmentSpendBinding.btcEditText.setSelection(it) }
        isBTCEditing = false
    }

    private fun setFiatEdit(value: String) {
        isFiatEditing = true
        fragmentSpendBinding.fiatEditText.setText(value)
        fragmentSpendBinding.fiatEditText.text?.length?.let { fragmentSpendBinding.fiatEditText.setSelection(it) }
        isFiatEditing = false
    }


    fun onBackPressed(): Boolean {
        if (fragmentSpendBinding.fragmentBroadcastTx.unsignedTxView.visibility == VISIBLE) {
            containerTransform(fragmentSpendBinding.composeBtn, fragmentSpendBinding.fragmentBroadcastTx.unsignedTxView)
            return false
        }
        Timber.i("onBackPressed: ")
        return true
    }

    fun setCollection(mCollection: PubKeyCollection) {
        this.mCollection = mCollection
    }

    private fun setPubKeySelector() {
        if (mCollection == null) {
            return
        }
        mCollection?.let {
            val labels: MutableList<String> = mutableListOf()
            val models: MutableList<PubKeyModel> = mutableListOf()


            for (pubKey in it.pubs) {
                if (pubKey.pubKey.toLowerCase().startsWith("bc") || pubKey.pubKey.toLowerCase().startsWith("tb") || pubKey.type == AddressTypes.BIP84) {
                    labels.add(pubKey.label)
                    models.add(pubKey)
                }
            }

            val adapter: ArrayAdapter<String> = ArrayAdapter(
                    requireContext(),
                    R.layout.dropdown_menu_popup_item, labels
            )
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.inputType = InputType.TYPE_NULL
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.threshold = labels.size
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.setAdapter(adapter)
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.onItemClickListener = AdapterView.OnItemClickListener { _, _, index, _ ->
                val selectPubKeyModel = models[index]
                viewModel.setPublicKey(selectPubKeyModel, viewLifecycleOwner)
                transactionComposer.setPubKey(selectPubKeyModel)

                view?.isEnabled = true
                view?.alpha = 1f
                fragmentSpendBinding.composeBtn.isEnabled = true
            }
            if (labels.size == 0) {
                return
            }
            fragmentSpendBinding.fragmentComposeTx.pubKeySelector.setText(labels.first(), false)
            viewModel.setPublicKey(it.pubs[0], viewLifecycleOwner)
        }
    }

    private fun setUpFee() {
        val multiplier = 10000
//        FEE_TYPE = PrefsUtil.getInstance(this).getValue(PrefsUtil.CURRENT_FEE_TYPE, SendActivity.FEE_NORMAL)
        feeLow = feeRepository.getLowFee().defaultPerKB.toLong() / 1000L
        feeMed = feeRepository.getNormalFee().defaultPerKB.toLong() / 1000L
        feeHigh = feeRepository.getHighFee().defaultPerKB.toLong() / 1000L

        val high = feeHigh / 2 + feeHigh
        val feeHighSliderValue = (high * multiplier)
        val feeMedSliderValue = (feeMed * multiplier)
        val valueTo = (feeHighSliderValue - multiplier).toFloat()
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.stepSize = 1F
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.valueTo = if (valueTo <= 0) feeHighSliderValue.toFloat() else valueTo
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.valueTo = if (valueTo <= 0) feeHighSliderValue.toFloat() else valueTo
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.valueFrom = 1F
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.setLabelFormatter { i: Float ->
            val value = (i + multiplier) / multiplier
            val formatted = "${decimalFormat.format(value)} sats/b"
            fragmentSpendBinding.fragmentComposeTx.feeSelector.selectedFeeRate.text = formatted
            formatted
        }
        if (feeLow == feeMed && feeMed == feeHigh) {
            feeLow = (feeMed.toDouble() * 0.85).toLong()
            feeHigh = (feeMed.toDouble() * 1.15).toLong()
            val loSf = SuggestedFee()
            loSf.defaultPerKB = BigInteger.valueOf(feeLow * 1000L)
            feeRepository.setLowFee(loSf)
            val hiSf = SuggestedFee()
            hiSf.defaultPerKB = BigInteger.valueOf(feeHigh * 1000L)
            feeRepository.setHighFee(hiSf)
        } else if (feeLow == feeMed || feeMed == feeMed) {
            feeMed = (feeLow + feeHigh) / 2L
            val miSf = SuggestedFee()
            miSf.defaultPerKB = BigInteger.valueOf(feeHigh * 1000L)
            feeRepository.setNormalFee(miSf)
        }
        if (feeLow < 1L) {
            feeLow = 1L
            val loSf = SuggestedFee()
            loSf.defaultPerKB = BigInteger.valueOf(feeLow * 1000L)
            feeRepository.setLowFee(loSf)
        }
        if (feeMed < 1L) {
            feeMed = 1L
            val miSf = SuggestedFee()
            miSf.defaultPerKB = BigInteger.valueOf(feeMed * 1000L)
            feeRepository.setNormalFee(miSf)
        }
        if (feeHigh < 1L) {
            feeHigh = 1L
            val hiSf = SuggestedFee()
            hiSf.defaultPerKB = BigInteger.valueOf(feeHigh * 1000L)
            feeRepository.setHighFee(hiSf)
        }
        fragmentSpendBinding.fragmentComposeTx.feeSelector.selectedFeeRateLayman.text = getString(R.string.normal)
        feeRepository.sanitizeFee()
        fragmentSpendBinding.fragmentComposeTx.feeSelector.selectedFeeRate.text = ("$feeMed sats/b")
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.value = (feeMedSliderValue - multiplier + 1).toFloat()
        setFeeLabels()
        viewModel.setFee(fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.value)
        var nbBlocks = 6
        fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.addOnChangeListener { slider, sliderVal, fromUser ->
            val value = (sliderVal + multiplier) / multiplier
            var pct = 0F
            if (value <= feeLow) {
                pct = feeLow / value
                nbBlocks = ceil(pct * 24.0).toInt()
            } else if (value >= feeHigh.toFloat()) {
                pct = feeHigh / value
                nbBlocks = ceil(pct * 2.0).toInt()
                if (nbBlocks < 1) {
                    nbBlocks = 1
                }
            } else {
                pct = feeMed / value
                nbBlocks = ceil(pct * 6.0).toInt()
            }
            fragmentSpendBinding.fragmentComposeTx.feeSelector.estBlockTime.text = "$nbBlocks blocks"
            if (nbBlocks > 50) {
                fragmentSpendBinding.fragmentComposeTx.feeSelector.estBlockTime.text = "50+ blocks"
            }
            setFeeLabels()
            viewModel.setFee(value * 1000)
        }
        fragmentSpendBinding.fragmentComposeTx.feeSelector.estBlockTime.text = "$nbBlocks blocks"
    }

    private fun setFeeLabels() {
        if (fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.valueTo <= 0) {
            return
        }
        val sliderValue: Float = fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.value / fragmentSpendBinding.fragmentComposeTx.feeSelector.feeSlider.valueTo
        val sliderInPercentage = sliderValue * 100
        if (sliderInPercentage < 33) {
            fragmentSpendBinding.fragmentComposeTx.feeSelector.selectedFeeRateLayman.setText(R.string.low)
        } else if (sliderInPercentage > 33 && sliderInPercentage < 66) {
            fragmentSpendBinding.fragmentComposeTx.feeSelector.selectedFeeRateLayman.setText(R.string.normal)
        } else if (sliderInPercentage > 66) {
            fragmentSpendBinding.fragmentComposeTx.feeSelector.selectedFeeRateLayman.setText(R.string.urgent)
        }
    }

    private fun generateQRCode(uri: String) {
        viewModel.viewModelScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                fragmentSpendBinding.fragmentBroadcastTx.psbtQRCode.setContent(UR.fromBytes(RegistryType.CRYPTO_PSBT.type,Hex.decode(uri)));
            }
        }
    }
}
