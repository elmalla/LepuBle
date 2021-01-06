package com.lepu.lepuble.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.jeremyliao.liveeventbus.LiveEventBus
import com.lepu.lepuble.R
import com.lepu.lepuble.ble.Er1BleInterface
import com.lepu.lepuble.ble.obj.Er1DataController
import com.lepu.lepuble.objs.Bluetooth
import com.lepu.lepuble.vals.EventMsgConst
import com.lepu.lepuble.viewmodel.Er1ViewModel
import com.lepu.lepuble.viewmodel.MainViewModel
import com.lepu.lepuble.views.EcgBkg
import com.lepu.lepuble.views.EcgView
import kotlinx.android.synthetic.main.fragment_er1.*
import java.text.SimpleDateFormat
import kotlin.math.floor

private const val ARG_ER1_DEVICE = "er1_device"

class Er1Fragment : Fragment() {

    private lateinit var bleInterface: Er1BleInterface

    private val model: Er1ViewModel by viewModels()
    private val activityModel: MainViewModel by activityViewModels()

    private lateinit var ecgBkg: EcgBkg
    private lateinit var ecgView: EcgView

    private lateinit var viewEcgBkg: RelativeLayout
    private lateinit var viewEcgView: RelativeLayout

    private var device: Bluetooth? = null


    /**
     * rt wave
     */
    private val waveHandler = Handler()

    inner class WaveTask : Runnable {
        override fun run() {
            if (!runWave) {
                return
            }

            val interval: Int = when {
                Er1DataController.dataRec.size > 250 -> {
                    30
                }
                Er1DataController.dataRec.size > 150 -> {
                    35
                }
                Er1DataController.dataRec.size > 75 -> {
                    40
                }
                else -> {
                    45
                }
            }

            waveHandler.postDelayed(this, interval.toLong())
//            LogUtils.d("DataRec: ${Er1DataController.dataRec.size}, delayed $interval")

            val temp = Er1DataController.draw(5)
            model.dataSrc.value = Er1DataController.feed(model.dataSrc.value, temp)
        }
    }

    private var runWave = false
    private fun startWave() {
        if (runWave) {
            return
        }
        runWave = true
        waveHandler.post(WaveTask())
    }

    private fun stopWave() {
        runWave = false
        Er1DataController.clear()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        arguments?.let {
//            device = it.getParcelable(ARG_ER1_DEVICE)
//            LogUtils.d("instance: ${device?.name}")
//            connect()
//        }
        bleInterface = Er1BleInterface()
        bleInterface.setViewModel(model)
        addLiveDataObserver()
        addLiveEventObserver()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val v = inflater.inflate(R.layout.fragment_er1, container, false)

        // add view
        viewEcgBkg = v.findViewById<RelativeLayout>(R.id.ecg_bkg)
        viewEcgView = v.findViewById<RelativeLayout>(R.id.ecg_view)

        viewEcgBkg.post {
            initEcgView()
        }

        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    @ExperimentalUnsignedTypes
    private fun initView() {
        get_file_list.setOnClickListener {
            bleInterface.getFileList()
        }

        /**
         * 默认下载第一条数据
         */
        download_file.setOnClickListener {
            if (bleInterface.fileList == null || bleInterface.fileList!!.size == 0) {
                Toast.makeText(activity, "请先获取数据列表或数据列表为空", Toast.LENGTH_SHORT).show()
            } else {
                val name = bleInterface.fileList!!.fileList[0]
                bleInterface.downloadFile(name)
            }

        }

        get_rt_data.setOnClickListener {
            bleInterface.runRtTask()
            startWave()
        }
    }

    private fun initEcgView() {
        // cal screen
        val dm =resources.displayMetrics
        val index = floor(viewEcgBkg.width / dm.xdpi * 25.4 / 25 * 125).toInt()
        Er1DataController.maxIndex = index

        val mm2px = 25.4f / dm.xdpi
        Er1DataController.mm2px = mm2px

//        LogUtils.d("max index: $index", "mm2px: $mm2px")

        viewEcgBkg.measure(0, 0)
        ecgBkg = EcgBkg(context)
        viewEcgBkg.addView(ecgBkg)

        viewEcgView.measure(0, 0)
        ecgView = EcgView(context)
        viewEcgView.addView(ecgView)

        ecg_view.visibility = View.GONE
    }

    // Er1ViewModel
    private fun addLiveDataObserver(){

        activityModel.er1DeviceName.observe(this, {
            if (it == null) {
                device_sn.text = "未绑定设备"
            } else {
                device_sn.text = it
            }
        })

        model.dataSrc.observe(this, {
            if (this::ecgView.isInitialized) {
                ecgView.setDataSrc(it)
                ecgView.invalidate()
            }
        })

        model.er1.observe(this, {
            device_sn.text = "SN：${it.sn}"
        })

        model.connect.observe(this, {
            if (it) {
                ble_state.setImageResource(R.mipmap.bluetooth_ok)
                ecg_view.visibility = View.VISIBLE
                battery.visibility = View.VISIBLE
                battery_left_duration.visibility = View.VISIBLE
            } else {
                ble_state.setImageResource(R.mipmap.bluetooth_error)
                ecg_view.visibility = View.INVISIBLE
                battery.visibility = View.INVISIBLE
                battery_left_duration.visibility = View.INVISIBLE
                stopWave()
            }
        })

        model.duration.observe(this, {
            if (it == 0) {
                measure_duration.text = "?"
                start_at.text = "?"
            } else {
                val day = it/60/60/24
                val hour = it/60/60 % 24
                val minute = it/60 % 60

                val start = System.currentTimeMillis() - it*1000
                start_at.text = SimpleDateFormat("yyyy/MM/dd HH:mm").format(start)
                if (day != 0) {
                    measure_duration.text = "$day 天 $hour 小时 $minute 分钟"
                } else {
                    measure_duration.text = "$hour 小时 $minute 分钟"
                }
            }
        })

        model.battery.observe(this, {
            battery.setImageLevel(it)
        })

        model.hr.observe(this, {
            if (it == 0) {
                hr.text = "?"
            } else {
                hr.text = it.toString()
            }
        })
    }


    /**
     * observe LiveDataBus
     * receive from KcaBleInterface
     * 考虑直接从interface来控制，不需要所有的都传递
     */
    private fun addLiveEventObserver() {
        LiveEventBus.get(EventMsgConst.EventDeviceChoosen)
                .observe(this, {
                    connect(it as Bluetooth)
                })
    }

    @SuppressLint("UseRequireInsteadOfGet")
    private fun connect(b: Bluetooth) {
        this@Er1Fragment.context?.let { bleInterface.connect(it, b.device) }
    }

    companion object {
        @JvmStatic
        fun newInstance(b: Bluetooth) =
            Er1Fragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_ER1_DEVICE, b)
                }
            }

        @JvmStatic
        fun newInstance() = Er1Fragment()
    }
}